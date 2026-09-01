package com.amalitech.labresultsvalidator.common.exceptions;

/**
 * Thrown when a refresh-token-based re-authentication attempt fails — missing, invalid, or
 * belonging to an inactive user. Mapped to 401 so the client knows to redirect to login, rather
 * than falling through to the generic-exception handler's 500.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
