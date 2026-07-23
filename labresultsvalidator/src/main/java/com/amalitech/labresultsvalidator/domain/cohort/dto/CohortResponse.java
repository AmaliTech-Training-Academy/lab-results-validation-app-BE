package com.amalitech.labresultsvalidator.domain.cohort.dto;

import java.time.LocalDate;
import java.util.UUID;

public record CohortResponse(
    UUID id,
    String name,
    LocalDate startDate,
    LocalDate endDate,
    String lifecycleState,
    boolean isLocked,
    String sharepointFolderUrl
) {}
