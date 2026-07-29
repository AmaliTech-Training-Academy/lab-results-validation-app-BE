package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SyncRunResponse(
    UUID id,
    UUID cohortId,
    CohortSyncJobStatus status,
    OffsetDateTime startedAt,
    OffsetDateTime completedAt,
    UUID triggeredBy,
    String targetItemId
) {
    public static SyncRunResponse from(CohortSyncJob job) {
        return new SyncRunResponse(
            job.getId(),
            job.getCohort().getId(),
            job.getStatus(),
            job.getStartedAt(),
            job.getCompletedAt(),
            job.getTriggeredBy(),
            job.getTargetItemId()
        );
    }
}