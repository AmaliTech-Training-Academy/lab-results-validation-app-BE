package com.amalitech.labresultsvalidator.domain.sync.entity;

public enum CohortSyncJobStatus {
    RUNNING,
    COMPLETED,
    /** The run finished, but at least one file failed (unreadable, unparsable, or failed to archive). */
    PARTIAL,
    FAILED,
    /**
     * The run reached at least one file, and every one of them was unchanged since the previous
     * run — nothing new, changed, or failed. Distinct from {@code COMPLETED} so "nothing to do"
     * (an admin's edit hadn't landed yet) doesn't read identically to "genuinely nothing to
     * grade" or "everything failed to read" — all three previously collapsed onto COMPLETED with
     * every count at zero.
     */
    SKIPPED
}