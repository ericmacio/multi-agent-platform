package com.cognizant.emk.multiagent.domain.shared;

/**
 * Root of the domain exception hierarchy.
 *
 * <p>All domain-thrown exceptions extend this class. Concrete per-context subclasses
 * (e.g. {@code DuplicateAgentNameException}) live in their bounded-context packages and
 * extend one of the framework-shaped subclasses below.
 */
public abstract class BusinessException extends RuntimeException {

    protected BusinessException(String message) {
        super(message);
    }

    protected BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
