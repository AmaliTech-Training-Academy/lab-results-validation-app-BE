package com.amalitech.labresultsvalidator.domain.standup.dto;

import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandUpJob;
import com.amalitech.labresultsvalidator.domain.standup.entity.CohortStandUpJobStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StandupRunResponse(
    UUID id,
    UUID cohortId,
    CohortStandUpJobStatus status,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    UUID triggeredBy
) {
    public static StandupRunResponse from(CohortStandUpJob job) {
        return new StandupRunResponse(
            job.getId(),
            job.getCohort().getId(),
            job.getStatus(),
            job.getStartedAt(),
            job.getCompletedAt(),
            job.getTriggeredBy()
        );
    }
}
