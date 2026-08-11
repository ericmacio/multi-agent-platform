package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.regex.Pattern;

/**
 * Public identifier of an API-key pair — what callers send in the {@code X-Client-Id}
 * header (design §8.4).
 *
 * <p>Constrained to {@code [A-Za-z0-9_-]+} (URL- and header-safe) and capped at 64
 * characters to match the {@code api_keys.client_id} column (design §5). Construction
 * throws {@link ValidationException} with field {@code "clientId"} on any violation,
 * so the REST adapter can surface it as a per-field RFC 7807 error.
 *
 * <p>Shipped in US-04-001 because {@link SystemPrincipal} requires it at the type level;
 * the full {@code ApiKey} aggregate and repository port that build on top live in
 * US-04-002.
 */
public record ClientId(String value) {

    private static final int MAX_LENGTH = 64;
    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]+$");

    public ClientId {
        if (value == null || value.isBlank()) {
            throw new ValidationException("clientId", "must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException("clientId", "must be at most " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new ValidationException("clientId", "must contain only [A-Za-z0-9_-] characters");
        }
    }
}
