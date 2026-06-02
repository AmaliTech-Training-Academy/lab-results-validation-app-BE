package com.amalitech.labresultsvalidator.common.exceptions;

public class GlobalExceptionHandler extends RuntimeException {
    /**
     * Constructs a new GlobalExceptionHandler with the given message.
     *
     * @param message the detail message
     */
    public GlobalExceptionHandler(final String message) {
        super(message);
    }
}
