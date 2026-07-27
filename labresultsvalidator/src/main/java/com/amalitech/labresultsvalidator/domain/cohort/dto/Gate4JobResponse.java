package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortGate4Job;
import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortGate4JobStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Gate4JobResponse(
    UUID id,
    UUID cohortId,
    CohortGate4JobStatus status,
    OffsetDateTime startedAt
) {
    public static Gate4JobResponse from(CohortGate4Job job) {
        return new Gate4JobResponse(
            job.getId(),
            job.getCohort().getId(),
            job.getStatus(),
            job.getStartedAt()
        );
    }
}
