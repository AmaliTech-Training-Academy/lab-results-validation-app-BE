package com.amalitech.labresultsvalidator.common.csv;

import java.util.List;

/**
 * Outcome of parsing a CSV file: the rows that bound successfully and the per-row errors for
 * those that did not.
 *
 * <p>This follows the partial-success model — valid rows are returned for the caller to commit
 * even when other rows fail. The {@code *Count} accessors map directly onto the
 * {@code total_rows / accepted_rows / rejected_rows} counters of the {@code csv_uploads} record.
 *
 * @param <T>       the bound bean type
 * @param validRows rows that bound without error, each with its source line number
 * @param errors    per-row binding/validation errors
 */
public record CsvParseResult<T>(List<ParsedRow<T>> validRows, List<CsvRowError> errors) {

    /** @return total data rows processed (valid + rejected). */
    public int totalRows() {
        return validRows.size() + errors.size();
    }

    /** @return number of rows that bound successfully. */
    public int acceptedCount() {
        return validRows.size();
    }

    /** @return number of rows that failed and were rejected. */
    public int rejectedCount() {
        return errors.size();
    }
}
