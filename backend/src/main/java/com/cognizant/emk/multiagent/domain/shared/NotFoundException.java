package com.cognizant.emk.multiagent.domain.shared;

/** Maps to HTTP 404 in the REST adapter. */
public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(message);
    }
}
