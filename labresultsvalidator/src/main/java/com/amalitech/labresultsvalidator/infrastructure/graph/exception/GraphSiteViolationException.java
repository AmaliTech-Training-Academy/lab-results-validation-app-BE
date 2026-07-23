package com.amalitech.labresultsvalidator.infrastructure.graph.exception;

public class GraphSiteViolationException extends RuntimeException {

    public GraphSiteViolationException(String message) {
        super(message);
    }

    public GraphSiteViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
