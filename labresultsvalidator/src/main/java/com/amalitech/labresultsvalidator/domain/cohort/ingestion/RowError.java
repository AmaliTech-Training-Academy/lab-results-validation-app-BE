package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import java.util.UUID;

/**
 * Mirrors {@code GateError}'s shape — a row (or sheet) that failed validation and was skipped.
 * {@code instructorContactId} is the resolved reviewer for this row, if any; null routes the
 * error to the admin notification digest instead of an instructor digest.
 */
public record RowError(String file, String location, String rule, String message, UUID instructorContactId) {
}
