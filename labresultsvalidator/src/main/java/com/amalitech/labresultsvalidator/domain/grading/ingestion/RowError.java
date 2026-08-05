package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import java.util.UUID;

/**
 * Mirrors {@code GateError}'s shape — a row (or sheet) that failed validation and was skipped.
 * {@code instructorContactId} is the resolved reviewer for this row, if any; null routes the
 * error to the admin notification digest instead of an instructor digest.
 *
 * <p>{@code labTitle} is the raw Lab Title cell, kept so a digest can group rows by module (C3 AC2).
 * Deliberately the raw string rather than a resolved {@code labId}: validation returns at the first
 * failing check, so most rejected rows never reach lab resolution and would otherwise have no module
 * at all. Null for sheet-level errors and for rows whose Lab Title was blank.
 */
public record RowError(String file, String location, String rule, String message,
                      UUID instructorContactId, String labTitle) {
}
