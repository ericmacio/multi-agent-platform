package com.cognizant.emk.multiagent.domain.auth;

import java.util.Objects;

/**
 * API-key-authenticated machine principal (design §8.4 / §8.6).
 *
 * <p>Set on the Spring Security context by the {@code ApiKeyAuthenticationFilter}
 * (delivered in US-04-009) once the submitted {@code X-Client-Id} + {@code X-Api-Key}
 * pair has been validated. Carries only the public {@link ClientId} — the API-key
 * cleartext and its BCrypt hash never reach this principal.
 *
 * <p>Authorization scope (design §8.6): full chat capabilities under the principal's
 * own ownership; explicitly excluded from {@code /admin/**} and from end-user-owned
 * resources. Those rules are enforced at the URL / method-security layer; this record
 * is only the typed identity that the rules pattern match on.
 */
public record SystemPrincipal(ClientId clientId) implements Principal {

    public SystemPrincipal {
        Objects.requireNonNull(clientId, "clientId");
    }
}
