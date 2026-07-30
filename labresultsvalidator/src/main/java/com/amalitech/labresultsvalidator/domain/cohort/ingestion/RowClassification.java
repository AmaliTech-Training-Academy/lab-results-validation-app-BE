package com.amalitech.labresultsvalidator.domain.cohort.ingestion;

import com.amalitech.labresultsvalidator.domain.cohort.entity.LabResult;

/**
 * The result of classifying one {@link ValidatedScoreRow} (B8). {@code existing} is populated for
 * {@code CHANGED} (the committed row being updated) and, when one exists, for {@code DUPLICATE}
 * (so the conflict record can reference the already-committed row alongside the incoming ones).
 */
public record RowClassification(ClassificationKind kind, ValidatedScoreRow row, LabResult existing) {
}
