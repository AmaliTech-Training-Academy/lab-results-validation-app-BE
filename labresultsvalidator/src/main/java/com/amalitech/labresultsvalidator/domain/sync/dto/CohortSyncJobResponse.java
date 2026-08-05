package com.amalitech.labresultsvalidator.domain.sync.dto;

import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJob;
import com.amalitech.labresultsvalidator.domain.sync.entity.CohortSyncJobStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CohortSyncJobResponse(
    UUID id,
    UUID cohortId,
    CohortSyncJobStatus status,
    OffsetDateTime startedAt
) {
    public static CohortSyncJobResponse from(CohortSyncJob job) {
        return new CohortSyncJobResponse(
            job.getId(),
            job.getCohort().getId(),
            job.getStatus(),
            job.getStartedAt()
        );
    }
}