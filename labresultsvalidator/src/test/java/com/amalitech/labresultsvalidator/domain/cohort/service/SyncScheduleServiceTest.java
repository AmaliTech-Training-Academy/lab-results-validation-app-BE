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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncScheduleServiceTest {

    @Mock
    private SyncScheduleRepository syncScheduleRepository;

    @Mock
    private CohortRepository cohortRepository;

    @Mock
    private CohortSyncService cohortSyncService;

    @Mock
    private TaskScheduler taskScheduler;

    private SyncScheduleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SyncScheduleService(syncScheduleRepository, cohortRepository, cohortSyncService, taskScheduler);
        ReflectionTestUtils.setField(service, "defaultTimezone", "GMT");
        // lenient: tests that throw before reaching registerSchedule() never touch this stub.
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(CronTrigger.class)))
            .thenReturn(mock(ScheduledFuture.class));
        lenient().when(syncScheduleRepository.save(any(SyncSchedule.class))).thenAnswer(inv -> {
            SyncSchedule saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
    }

    @Test
    void create_globalDailySchedule_savesAndRegistersWithoutCohort() {
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 30))
            .enabled(true)
            .build();

        SyncScheduleResponse response = service.create(request);

        assertThat(response.cohortId()).isNull();
        assertThat(response.frequency()).isEqualTo(ScheduleFrequency.DAILY);
        assertThat(response.timezone()).isEqualTo("GMT");
        assertThat(response.enabled()).isTrue();
        verify(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void create_weeklyWithoutDayOfWeek_throwsUnprocessable() {
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.WEEKLY)
            .timeOfDay(LocalTime.of(8, 0))
            .build();

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(UnprocessableEntityException.class);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void create_unknownCohort_throwsResourceNotFound() {
        UUID cohortId = UUID.randomUUID();
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .cohortId(cohortId)
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_disabledSchedule_savesButDoesNotRegister() {
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .enabled(false)
            .build();

        service.create(request);

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void rehydrate_loadsAndRegistersEnabledSchedulesOnStartup() {
        SyncSchedule schedule = SyncSchedule.builder()
            .id(UUID.randomUUID())
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .timezone("GMT")
            .enabled(true)
            .build();
        when(syncScheduleRepository.findByEnabledTrue()).thenReturn(List.of(schedule));

        ReflectionTestUtils.invokeMethod(service, "rehydrate");

        verify(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void update_disablingSchedule_cancelsWithoutRescheduling() {
        UUID id = UUID.randomUUID();
        SyncSchedule existing = SyncSchedule.builder()
            .id(id)
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .timezone("GMT")
            .enabled(true)
            .build();
        when(syncScheduleRepository.findById(id)).thenReturn(Optional.of(existing));

        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(9, 0))
            .enabled(false)
            .build();

        SyncScheduleResponse response = service.update(id, request);

        assertThat(response.enabled()).isFalse();
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
    }

    @Test
    void update_unknownId_throwsResourceNotFound() {
        UUID id = UUID.randomUUID();
        when(syncScheduleRepository.findById(id)).thenReturn(Optional.empty());

        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .build();

        assertThatThrownBy(() -> service.update(id, request)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_existingSchedule_cancelsAndRemoves() {
        UUID id = UUID.randomUUID();
        SyncSchedule existing = SyncSchedule.builder()
            .id(id)
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .timezone("GMT")
            .enabled(true)
            .build();
        when(syncScheduleRepository.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);

        verify(syncScheduleRepository, times(1)).delete(existing);
    }

    @Test
    void runSchedule_globalSchedule_triggersSyncForAll() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .frequency(ScheduleFrequency.DAILY)
            .timeOfDay(LocalTime.of(8, 0))
            .build();

        service.create(request);

        verify(taskScheduler).schedule(captor.capture(), any(CronTrigger.class));
        captor.getValue().run();
        verify(cohortSyncService).triggerScheduledSyncForAll();
    }

    @Test
    void runSchedule_cohortScopedSchedule_triggersSyncForCohort() {
        UUID cohortId = UUID.randomUUID();
        Cohort cohort = Cohort.builder().id(cohortId).build();
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort));

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        SyncScheduleRequest request = SyncScheduleRequest.builder()
            .cohortId(cohortId)
            .frequency(ScheduleFrequency.WEEKLY)
            .dayOfWeek(DayOfWeek.MONDAY)
            .timeOfDay(LocalTime.of(8, 0))
            .build();

        service.create(request);

        verify(taskScheduler).schedule(captor.capture(), any(CronTrigger.class));
        captor.getValue().run();
        verify(cohortSyncService).triggerScheduledSyncForCohort(cohortId);
    }
}
