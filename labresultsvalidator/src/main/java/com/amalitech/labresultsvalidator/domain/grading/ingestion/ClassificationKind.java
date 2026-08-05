package com.amalitech.labresultsvalidator.domain.grading.ingestion;

/** B8 — the four possible outcomes of classifying a validated row against committed data. */
public enum ClassificationKind {
    NEW,
    UNCHANGED,
    CHANGED,
    DUPLICATE
}
