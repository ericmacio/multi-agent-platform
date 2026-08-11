package com.cognizant.emk.multiagent.domain.shared;

/** Maps to HTTP 403 in the REST adapter. */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(message);
    }
}
