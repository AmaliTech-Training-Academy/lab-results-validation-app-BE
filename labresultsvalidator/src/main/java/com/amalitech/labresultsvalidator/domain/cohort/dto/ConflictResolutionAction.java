package com.amalitech.labresultsvalidator.domain.cohort.dto;

/** B10 — how an admin resolves a held ingestion conflict. */
public enum ConflictResolutionAction {
    /** The already-committed row stands; the incoming row is discarded. */
    KEEP_EXISTING,
    /** The incoming row becomes authoritative — updates the existing row, or creates one if none exists. */
    KEEP_INCOMING,
    /** Neither row is committed. */
    REJECT
}
