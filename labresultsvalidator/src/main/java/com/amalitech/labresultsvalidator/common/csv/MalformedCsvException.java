package com.amalitech.labresultsvalidator.common.csv;

/**
 * Thrown for whole-file structural problems that make a CSV unusable: an empty or unreadable
 * file, malformed CSV, missing required header columns, or a row count over the configured cap.
 *
 * <p>Distinct from per-row binding errors (which are collected into {@link CsvParseResult});
 * this aborts the entire upload. Mapped to HTTP 422 by the global exception handler.
 */
public class MalformedCsvException extends RuntimeException {

    public MalformedCsvException(String message) {
        super(message);
    }

    public MalformedCsvException(String message, Throwable cause) {
        super(message, cause);
    }
}
