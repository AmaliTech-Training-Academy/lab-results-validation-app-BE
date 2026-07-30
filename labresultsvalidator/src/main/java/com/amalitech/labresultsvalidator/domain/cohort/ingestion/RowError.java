package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

/** Mirrors {@code GateError}'s shape — a row (or sheet) that failed validation and was skipped. */
public record RowError(String file, String location, String rule, String message) {
}
