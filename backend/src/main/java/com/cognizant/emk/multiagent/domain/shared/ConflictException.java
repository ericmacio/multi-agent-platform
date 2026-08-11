package com.cognizant.emk.multiagent.domain.shared;

/** Maps to HTTP 409 in the REST adapter. Thrown when a business invariant is violated. */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(message);
    }
}
