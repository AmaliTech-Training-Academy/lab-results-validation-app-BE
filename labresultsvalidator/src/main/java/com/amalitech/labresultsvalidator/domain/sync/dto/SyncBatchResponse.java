package com.amalitech.labresultsvalidator.domain.sync.dto;

import java.util.List;
import java.util.UUID;

public record SyncBatchResponse(
    int triggered,
    int skipped,
    List<UUID> triggeredCohortIds
) {}