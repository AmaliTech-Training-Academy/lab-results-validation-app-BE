package com.amalitech.labresultsvalidator.domain.enums;

public enum UploadStatus {
    /** Upload is currently being processed. */
    PROCESSING,
    /** Upload completed successfully — all rows accepted. */
    COMPLETED,
    /** Upload completed with some rows accepted and some rejected. */
    PARTIAL,
    /** Upload failed — no rows were accepted. */
    FAILED
}
