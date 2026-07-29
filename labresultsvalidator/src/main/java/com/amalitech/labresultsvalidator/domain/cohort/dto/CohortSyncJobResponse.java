package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortSyncJobStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CohortSyncJobResponse(
    UUID id,
    UUID cohortId,
    CohortSyncJobStatus status,
    OffsetDateTime startedAt,
    String targetItemId
) {
    public static CohortSyncJobResponse from(CohortSyncJob job) {
        return new CohortSyncJobResponse(
            job.getId(),
            job.getCohort().getId(),
            job.getStatus(),
            job.getStartedAt(),
            job.getTargetItemId()
        );
    }
}