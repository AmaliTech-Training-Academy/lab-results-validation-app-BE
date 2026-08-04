package com.amalitech.labresultsvalidator.domain.cohort.dto;

import java.util.UUID;

/** One entry from an {@code IngestionRun.errorReportJson} array — mirrors {@code RowError}'s shape. */
public record RowIssueSummary(String file, String location, String rule, String message, UUID instructorContactId) {
}
