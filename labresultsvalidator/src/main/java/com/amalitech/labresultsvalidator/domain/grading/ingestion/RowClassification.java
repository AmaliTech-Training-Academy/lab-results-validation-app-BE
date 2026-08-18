package com.amalitech.labresultsvalidator.domain.grading.ingestion;

import com.amalitech.labresultsvalidator.domain.grading.entity.LabResult;

import java.util.List;

/**
 * The result of classifying one {@link ValidatedScoreRow} (B8). {@code existing} is populated for
 * {@code CHANGED} (the committed row being updated) and, when one exists, for {@code DUPLICATE}
 * (so the conflict record can reference the already-committed row alongside the incoming ones).
 *
 * <p>{@code duplicateRows} holds <strong>every</strong> conflicting copy for a {@code DUPLICATE} and
 * is empty for every other kind. A duplicated row is one problem needing one decision (B10 AC1), so
 * the classifier emits a single {@code DUPLICATE} carrying the whole group rather than one per copy —
 * previously each copy became its own conflict, and resolution then asked the same question once per
 * copy and accepted contradictory answers. {@code row} stays the first copy of the group, so callers
 * that only need the identity ({@code learnerId}/{@code labId}) or a location to log are unaffected.
 */
public record RowClassification(
    ClassificationKind kind,
    ValidatedScoreRow row,
    LabResult existing,
    List<ValidatedScoreRow> duplicateRows
) {

    /** Non-duplicate kinds, which carry exactly the one row. */
    public RowClassification(ClassificationKind kind, ValidatedScoreRow row, LabResult existing) {
        this(kind, row, existing, List.of());
    }

    /** Every conflicting copy of a {@code DUPLICATE}; the single row for any other kind. */
    public List<ValidatedScoreRow> allRows() {
        return duplicateRows.isEmpty() ? List.of(row) : duplicateRows;
    }
}
