package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortStandUpJobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandUpJobResponse {
    private UUID id;
    private UUID cohortId;
    private CohortStandUpJobStatus status;
    private OffsetDateTime startedAt;
}
