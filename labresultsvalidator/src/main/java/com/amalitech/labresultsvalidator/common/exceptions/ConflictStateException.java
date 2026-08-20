package com.amalitech.labresultsvalidator.common.exceptions;

/**
 * The target exists and the request is well-formed, but its current state does not allow the
 * operation — e.g. deciding an ingestion conflict that has already been decided (B10). Maps to
 * {@code 409 Conflict}.
 *
 * <p>Distinct from {@link DuplicateResourceException}, which is also 409 but means "a resource like
 * this already exists", and from {@link UnprocessableEntityException} (422), which means the request
 * itself cannot be acted on. A second decision on a resolved duplicate is a lost-update collision,
 * not a malformed request, so it answers 409.
 */
public class ConflictStateException extends RuntimeException {

    public ConflictStateException(String message) {
        super(message);
    }
}
