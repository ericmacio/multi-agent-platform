package com.cognizant.emk.multiagent.infrastructure.error;

/**
 * Wraps a Spring {@link org.springframework.dao.DataAccessException} at the
 * persistence-adapter boundary so the application layer never sees Spring
 * types directly (design §9.1, REQ-ARC-007, REQ-ARC-003).
 *
 * <p>Surfaces as 500 {@code INTERNAL_ERROR} at the REST boundary. The cause is
 * logged at {@code ERROR} with class name only; the response body carries the
 * sanitized generic detail (REQ-API-004, REQ-SEC-004).
 *
 * <p>Adapters SHOULD use the {@code JpaAccess} helper under
 * {@code infrastructure/persistence/adapter/} to bracket their JPA calls; the
 * helper translates {@code DataAccessException} into this type uniformly.
 */
public class DatabaseAccessException extends RuntimeException {

    public DatabaseAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
