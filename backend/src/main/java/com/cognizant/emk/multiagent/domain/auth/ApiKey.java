package com.cognizant.emk.multiagent.domain.auth;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Machine-to-machine API key aggregate (REQ-AUTH-007 / -012, design §4.1).
 *
 * <p>Holds the BCrypt-hashed key as a raw {@code String} ({@code apiKeyHash}); the
 * cleartext is never stored on the aggregate — it is shown once at creation time by
 * {@code CreateApiKeyService} (US-04-006) and discarded thereafter. The {@code label} is
 * normalized at construction: a {@code null} or blank value collapses to {@code null}
 * (the corresponding {@code api_keys.label} column is nullable per design §5).
 *
 * <p>Label length is enforced here so the invariant matches the {@code varchar(128)}
 * DB column without relying on the use-case layer.
 */
public record ApiKey(
        ClientId clientId,
        String apiKeyHash,
        String label,
        boolean disabled,
        OffsetDateTime createdAt) {

    private static final int MAX_LABEL_LENGTH = 128;

    public ApiKey {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(apiKeyHash, "apiKeyHash");
        Objects.requireNonNull(createdAt, "createdAt");
        if (apiKeyHash.isBlank()) {
            throw new ValidationException("apiKeyHash", "must not be blank");
        }
        label = normalizeLabel(label);
    }

    /** True when the API key may be used to authenticate (i.e. not soft-revoked). */
    public boolean isActive() {
        return !disabled;
    }

    /**
     * Returns a copy with {@code disabled} replaced; all other fields are preserved.
     */
    public ApiKey withDisabled(boolean newDisabled) {
        return new ApiKey(clientId, apiKeyHash, label, newDisabled, createdAt);
    }

    private static String normalizeLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LABEL_LENGTH) {
            throw new ValidationException(
                    "label", "must be at most " + MAX_LABEL_LENGTH + " characters");
        }
        return trimmed;
    }
}
