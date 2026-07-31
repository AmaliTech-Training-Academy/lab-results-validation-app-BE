package com.amalitech.labresultsvalidator.domain.cohort.service;

import com.amalitech.labresultsvalidator.common.exceptions.ResourceNotFoundException;
import com.amalitech.labresultsvalidator.common.exceptions.UnprocessableEntityException;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SyncScheduleRequest;
import com.amalitech.labresultsvalidator.domain.cohort.dto.SyncScheduleResponse;
import com.amalitech.labresultsvalidator.domain.cohort.entity.Cohort;
import com.amalitech.labresultsvalidator.domain.cohort.entity.ScheduleFrequency;
import com.amalitech.labresultsvalidator.domain.cohort.entity.SyncSchedule;
import com.amalitech.labresultsvalidator.domain.cohort.repository.CohortRepository;
import com.amalitech.labresultsvalidator.domain.cohort.repository.SyncScheduleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Owns the lifecycle of user-defined sync schedules: persists them and keeps an in-memory
 * {@link ScheduledFuture} per enabled schedule registered with Spring's {@link TaskScheduler},
 * so changes take effect immediately without an app restart. Single-instance only — there is no
 * cross-node claim mechanism, so running more than one app instance would fire each schedule once
 * per instance.
 */
@Service
@RequiredArgsConstructor
public class SyncScheduleService {

    private static final Logger LOG = LoggerFactory.getLogger(SyncScheduleService.class);

    private final SyncScheduleRepository syncScheduleRepository;
    private final CohortRepository cohortRepository;
    private final CohortSyncService cohortSyncService;
    private final TaskScheduler taskScheduler;

    @Value("${sync.schedule.zone:GMT}")
    private String defaultTimezone;

    private final Map<UUID, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    @PostConstruct
    void rehydrate() {
        List<SyncSchedule> enabled = syncScheduleRepository.findByEnabledTrue();
        enabled.forEach(this::register);
        LOG.info("[sync-schedule] rehydrated {} enabled schedule(s) on startup", enabled.size());
    }

    public SyncScheduleResponse create(SyncScheduleRequest request) {
        SyncSchedule schedule = SyncSchedule.builder()
            .name(request.getName())
            .cohort(resolveCohort(request.getCohortId()))
            .frequency(request.getFrequency())
            .timeOfDay(request.getTimeOfDay())
            .dayOfWeek(request.getDayOfWeek())
            .timezone(resolveTimezone(request.getTimezone()))
            .enabled(request.isEnabled())
            .build();
        validate(schedule);

        schedule = syncScheduleRepository.save(schedule);
        if (schedule.isEnabled()) {
            register(schedule);
        }
        return SyncScheduleResponse.from(schedule);
    }

    public List<SyncScheduleResponse> list() {
        return syncScheduleRepository.findAll().stream().map(SyncScheduleResponse::from).toList();
    }

    public SyncScheduleResponse get(UUID id) {
        return SyncScheduleResponse.from(getOrThrow(id));
    }

    public SyncScheduleResponse update(UUID id, SyncScheduleRequest request) {
        SyncSchedule schedule = getOrThrow(id);
        schedule.setName(request.getName());
        schedule.setCohort(resolveCohort(request.getCohortId()));
        schedule.setFrequency(request.getFrequency());
        schedule.setTimeOfDay(request.getTimeOfDay());
        schedule.setDayOfWeek(request.getDayOfWeek());
        schedule.setTimezone(resolveTimezone(request.getTimezone()));
        schedule.setEnabled(request.isEnabled());
        validate(schedule);

        schedule = syncScheduleRepository.save(schedule);
        cancel(schedule.getId());
        if (schedule.isEnabled()) {
            register(schedule);
        }
        return SyncScheduleResponse.from(schedule);
    }

    public void delete(UUID id) {
        SyncSchedule schedule = getOrThrow(id);
        cancel(schedule.getId());
        syncScheduleRepository.delete(schedule);
    }

    private SyncSchedule getOrThrow(UUID id) {
        return syncScheduleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Sync schedule not found with ID: " + id));
    }

    private Cohort resolveCohort(UUID cohortId) {
        if (cohortId == null) {
            return null;
        }
        return cohortRepository.findById(cohortId)
            .orElseThrow(() -> new ResourceNotFoundException("Cohort not found with ID: " + cohortId));
    }

    private String resolveTimezone(String timezone) {
        return (timezone == null || timezone.isBlank()) ? defaultTimezone : timezone;
    }

    private void validate(SyncSchedule schedule) {
        if (schedule.getFrequency() == ScheduleFrequency.WEEKLY && schedule.getDayOfWeek() == null) {
            throw new UnprocessableEntityException("dayOfWeek is required when frequency is WEEKLY");
        }
        try {
            ZoneId.of(schedule.getTimezone());
        } catch (DateTimeException ex) {
            throw new UnprocessableEntityException("Invalid timezone: " + schedule.getTimezone());
        }
    }

    private void register(SyncSchedule schedule) {
        CronTrigger trigger = new CronTrigger(toCron(schedule), ZoneId.of(schedule.getTimezone()));
        ScheduledFuture<?> future = taskScheduler.schedule(() -> runSchedule(schedule), trigger);
        activeTasks.put(schedule.getId(), future);
        LOG.info("[sync-schedule] registered schedule={} cron='{}' zone={}",
            schedule.getId(), toCron(schedule), schedule.getTimezone());
    }

    private void runSchedule(SyncSchedule schedule) {
        UUID cohortId = schedule.getCohort() != null ? schedule.getCohort().getId() : null;
        LOG.info("[sync-schedule] schedule={} firing (cohort={})", schedule.getId(), cohortId);
        if (cohortId == null) {
            cohortSyncService.triggerScheduledSyncForAll();
        } else {
            cohortSyncService.triggerScheduledSyncForCohort(cohortId);
        }
    }

    private void cancel(UUID scheduleId) {
        ScheduledFuture<?> future = activeTasks.remove(scheduleId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String toCron(SyncSchedule schedule) {
        int minute = schedule.getTimeOfDay().getMinute();
        int hour = schedule.getTimeOfDay().getHour();
        if (schedule.getFrequency() == ScheduleFrequency.DAILY) {
            return "0 %d %d * * *".formatted(minute, hour);
        }
        return "0 %d %d ? * %s".formatted(minute, hour, cronDayOfWeek(schedule.getDayOfWeek()));
    }

    private static String cronDayOfWeek(DayOfWeek dayOfWeek) {
        return dayOfWeek.name().substring(0, 3);
    }
}
