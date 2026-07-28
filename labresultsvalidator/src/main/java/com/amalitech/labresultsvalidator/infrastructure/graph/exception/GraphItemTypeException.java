package com.amalitech.labresultsvalidator.infrastructure.graph.exception;

public class GraphItemTypeException extends RuntimeException {

    public GraphItemTypeException(String message) {
        super(message);
    }

    public GraphItemTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
