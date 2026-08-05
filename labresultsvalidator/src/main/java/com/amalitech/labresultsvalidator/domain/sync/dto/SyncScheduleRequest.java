package com.amalitech.labresultsvalidator.domain.sync.dto;

import com.amalitech.labresultsvalidator.domain.sync.entity.ScheduleFrequency;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncScheduleRequest {

    private String name;

    /** Cohort to sync; omit to run the same "all eligible cohorts" batch as a manual sync-all. */
    private UUID cohortId;

    @NotNull(message = "Frequency is required")
    private ScheduleFrequency frequency;

    @NotNull(message = "Time of day is required")
    private LocalTime timeOfDay;

    /** Required when frequency is WEEKLY, ignored otherwise. */
    private DayOfWeek dayOfWeek;

    /** IANA zone ID; defaults to the app's configured sync timezone if omitted. */
    private String timezone;

    @Builder.Default
    private boolean enabled = true;
}
