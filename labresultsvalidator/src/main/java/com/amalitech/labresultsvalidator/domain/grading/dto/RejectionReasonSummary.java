package com.amalitech.labresultsvalidator.domain.grading.dto;

/**
 * How many rejected rows in a file carried a given rule code — the "why" behind a failure rate.
 * {@code description} translates the terse {@code rule} code (e.g. {@code R5-UNKNOWN-REVIEWER})
 * into a plain-language explanation, since the code alone means nothing outside the dev team.
 */
public record RejectionReasonSummary(String rule, String description, long count) {
}
