package com.amalitech.labresultsvalidator.common.csv;

import java.util.Set;

/**
 * Shared limits and accepted content types for CSV ingestion.
 */
public final class CsvConstants {

    /** Maximum number of data rows accepted in a single upload (PRD cap). */
    public static final int MAX_ROWS = 10_000;

    /** Content types browsers and clients commonly send for a CSV file. */
    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv",
            "application/csv",
            "application/vnd.ms-excel",
            "application/octet-stream",
            "text/plain");

    private CsvConstants() {
    }
}
