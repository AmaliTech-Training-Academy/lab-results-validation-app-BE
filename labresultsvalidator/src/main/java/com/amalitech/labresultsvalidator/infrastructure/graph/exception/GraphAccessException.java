package com.amalitech.labresultsvalidator.infrastructure.graph.exception;

public class GraphAccessException extends RuntimeException {

    public GraphAccessException(String message) {
        super(message);
    }

    public GraphAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
