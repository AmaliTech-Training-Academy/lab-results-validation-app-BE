package com.amalitech.labresultsvalidator.common.csv;

import java.util.List;
import java.util.Map;

/**
 * Outcome of parsing a CSV file: the rows that bound successfully and the per-row errors for
 * those that did not.
 *
 * <p>This follows the partial-success model — valid rows are returned for the caller to commit
 * even when other rows fail. The {@code *Count} accessors map directly onto the
 * {@code total_rows / accepted_rows / rejected_rows} counters of the {@code csv_uploads} record.
 *
 * <p>{@link #rawCellsByLine()} preserves the original (pre-binding) cell values of <em>every</em>
 * data line, keyed by 1-based source line number, with the inner map keyed by the trimmed,
 * upper-cased header column name. This lets a caller reconstruct a rejected row's original columns
 * even when the row failed OpenCSV binding and therefore has no bound bean (e.g. a corrections-only
 * download) — without it, such rows would carry only an error message and no data.
 *
 * @param <T>            the bound bean type
 * @param validRows      rows that bound without error, each with its source line number
 * @param errors         per-row binding/validation errors
 * @param rawCellsByLine the original cell values of every data line, by source line number
 */
public record CsvParseResult<T>(
        List<ParsedRow<T>> validRows, List<CsvRowError> errors,
        Map<Long, Map<String, String>> rawCellsByLine) {

    /**
     * Backward-compatible constructor for callers (mainly tests) that do not supply raw cell
     * values; {@link #rawCellsByLine()} defaults to an empty map.
     */
    public CsvParseResult(List<ParsedRow<T>> validRows, List<CsvRowError> errors) {
        this(validRows, errors, Map.of());
    }

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
