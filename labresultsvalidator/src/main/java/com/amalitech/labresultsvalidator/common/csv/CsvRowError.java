package com.amalitech.labresultsvalidator.common.csv;

/**
 * A single per-row validation/binding error produced while parsing a CSV file.
 *
 * <p>Collections of these are surfaced to the client as a row-level error report and map onto the
 * {@code error_report_json} column of the {@code csv_uploads} audit record.
 *
 * @param rowNumber the 1-based source line number the error occurred on
 * @param field     the offending column name, or {@code null}/empty when not field-specific
 * @param rule      the validation rule code that was broken (e.g. {@code "V5"}), or {@code null}
 * @param message   a human-readable description of what went wrong
 */
public record CsvRowError(long rowNumber, String field, String rule, String message) {

    /**
     * Convenience constructor for errors not tied to a specific rule code (e.g. OpenCSV binding
     * failures). Delegates with a {@code null} rule, keeping existing call sites source-compatible.
     *
     * @param rowNumber the 1-based source line number the error occurred on
     * @param field     the offending column name, or {@code null}/empty when not field-specific
     * @param message   a human-readable description of what went wrong
     */
    public CsvRowError(long rowNumber, String field, String message) {
        this(rowNumber, field, null, message);
    }
}
