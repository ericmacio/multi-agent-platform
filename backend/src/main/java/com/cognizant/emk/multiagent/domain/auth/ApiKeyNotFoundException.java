package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.shared.NotFoundException;

/**
 * Thrown when an admin operation references an unknown {@link ClientId}.
 *
 * <p>Maps to HTTP 404 {@code NOT_FOUND} via the existing {@code GlobalExceptionHandler}.
 * The message contains only the public {@code clientId} (safe to surface — it is the
 * public identifier, never the secret).
 */
public final class ApiKeyNotFoundException extends NotFoundException {

    public ApiKeyNotFoundException(ClientId clientId) {
        super("API key not found: " + clientId.value());
    }
}
