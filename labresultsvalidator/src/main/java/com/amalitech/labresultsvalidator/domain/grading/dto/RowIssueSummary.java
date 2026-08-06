package com.amalitech.labresultsvalidator.domain.grading.dto;

import java.util.UUID;

/**
 * One entry from an {@code IngestionRun.errorReportJson} array — mirrors {@code RowError}'s shape.
 *
 * <p>{@code labTitle} may be null on rows written before it was recorded; treat it as "module
 * unknown" rather than assuming it is always present.
 */
public record RowIssueSummary(String file, String location, String rule, String message,
                              UUID instructorContactId, String labTitle) {
}
