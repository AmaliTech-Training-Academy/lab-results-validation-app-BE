package com.amalitech.labresultsvalidator.common.csv;

/**
 * A successfully bound CSV row paired with the source line it came from.
 *
 * @param <T>        the bound bean type
 * @param lineNumber the 1-based source line number of this row
 * @param data       the bound bean
 */
public record ParsedRow<T>(long lineNumber, T data) {
}
