package com.amalitech.labresultsvalidator.domain.sync.entity;

public enum CohortSyncJobStatus {
    RUNNING,
    COMPLETED,
    /** The run finished, but at least one file failed (unreadable, unparsable, or failed to archive). */
    PARTIAL,
    FAILED
}