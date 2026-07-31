package com.amalitech.labresultsvalidator.domain.cohort.dto;

/** How many rejected rows in a file carried a given rule code — the "why" behind a failure rate. */
public record RejectionReasonSummary(String rule, long count) {
}
