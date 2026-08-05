package com.amalitech.labresultsvalidator.domain.sync.dto;

import com.amalitech.labresultsvalidator.domain.sync.entity.ScheduleFrequency;
import com.amalitech.labresultsvalidator.domain.sync.entity.SyncSchedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SyncScheduleResponse(
    UUID id,
    String name,
    UUID cohortId,
    ScheduleFrequency frequency,
    LocalTime timeOfDay,
    DayOfWeek dayOfWeek,
    String timezone,
    boolean enabled,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static SyncScheduleResponse from(SyncSchedule schedule) {
        return new SyncScheduleResponse(
            schedule.getId(),
            schedule.getName(),
            schedule.getCohort() != null ? schedule.getCohort().getId() : null,
            schedule.getFrequency(),
            schedule.getTimeOfDay(),
            schedule.getDayOfWeek(),
            schedule.getTimezone(),
            schedule.isEnabled(),
            schedule.getCreatedAt(),
            schedule.getUpdatedAt()
        );
    }
}
