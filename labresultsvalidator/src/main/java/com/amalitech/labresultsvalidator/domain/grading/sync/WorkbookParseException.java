package com.amalitech.labresultsvalidator.domain.grading.sync;

/**
 * Raised when Apache POI cannot open a downloaded workbook — corrupt, truncated, or an
 * unsupported format (B4 AC2).
 *
 * <p>Scoped to a single workbook on purpose: the caller records the failure and moves to the
 * next file rather than aborting the run (§4.5).
 */
public class WorkbookParseException extends Exception {

    public WorkbookParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
