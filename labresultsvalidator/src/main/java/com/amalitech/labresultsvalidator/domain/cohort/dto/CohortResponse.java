package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.entity.CohortLifecycleState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CohortResponse {
    private UUID id;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private CohortLifecycleState lifecycleState;
    private boolean isActive;
    private String sharepointFolderUrl;
    private OffsetDateTime createdAt;
}
