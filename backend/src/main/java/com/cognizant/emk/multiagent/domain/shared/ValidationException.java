package com.cognizant.emk.multiagent.domain.shared;

import java.util.Optional;

/**
 * Maps to HTTP 400 in the REST adapter. Thrown when an input violates a domain rule.
 *
 * <p>An optional {@code field} may be carried along so the REST adapter can populate a
 * per-field {@code errors[]} entry in the RFC 7807 problem-details response (design §9.3).
 * When no field is set, the exception's message becomes the response {@code detail}.
 */
public class ValidationException extends BusinessException {

    private final String field;

    public ValidationException(String message) {
        this(null, message);
    }

    public ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public Optional<String> field() {
        return Optional.ofNullable(field);
    }
}
