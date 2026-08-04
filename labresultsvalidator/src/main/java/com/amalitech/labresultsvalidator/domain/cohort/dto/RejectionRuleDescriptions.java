package com.amalitech.labresultsvalidator.domain.cohort.dto;

import java.util.Map;

/**
 * Plain-language explanation for every rule code that can appear in {@link RowIssueSummary#rule()}
 * — the codes are terse (e.g. {@code R5-UNKNOWN-REVIEWER}) so a non-engineer reading a sync
 * overview has no way to know what they mean without this lookup. Covers every rule code raised by
 * {@code ScoreRowParser}, {@code ScoreRowValidationService} and {@code LabResultCommitService} —
 * the only sources that feed a row into {@code errorReportJson}.
 */
final class RejectionRuleDescriptions {

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
        Map.entry("S2-MISSING-COLUMN",
            "The score sheet is missing a required column."),
        Map.entry("F1-BLANK-NSP",
            "The 'Name of NSP' cell is blank."),
        Map.entry("F1-BLANK-LAB-TITLE",
            "The 'Lab Title' cell is blank."),
        Map.entry("F1-BLANK-REVIEW-DATE",
            "The 'Review Date' cell is blank."),
        Map.entry("F3-INVALID-DATE",
            "The 'Review Date' cell does not contain a recognizable date."),
        Map.entry("F2-INVALID-SCORE",
            "The 'Total Score' cell does not contain a number."),
        Map.entry("F2-SCORE-OUT-OF-RANGE",
            "The 'Total Score' value falls outside the valid 0-100 range."),
        Map.entry("R1-UNKNOWN-NSP",
            "The trainee named in 'Name of NSP' could not be matched to a learner in this cohort."),
        Map.entry("R4-UNKNOWN-LAB",
            "The 'Lab Title' does not match any lab configured for this cohort."),
        Map.entry("R4-LAB-SPEC-MISMATCH",
            "The trainee's specialization does not match the specialization the lab is configured under."),
        Map.entry("R5-UNKNOWN-REVIEWER",
            "The 'Reviewer' named on the row does not match any active instructor."),
        Map.entry("COMMIT-FAILED",
            "The row passed validation but could not be saved due to a database error.")
    );

    private RejectionRuleDescriptions() {
    }

    static String describe(String rule) {
        return DESCRIPTIONS.getOrDefault(rule, "Row rejected by rule '" + rule + "'.");
    }
}
