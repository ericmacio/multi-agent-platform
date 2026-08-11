package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.domain.auth.ClientId;

/**
 * Technical port that produces a fresh API-key pair at creation time (REQ-AUTH-007).
 *
 * <p>The adapter delivered in v1 is {@code SecureRandomApiKeyGeneratorAdapter}, which
 * draws random bytes from a {@code SecureRandom} instance. The port sits in the
 * application layer so {@code CreateApiKeyService} (US-04-006) stays free of any direct
 * dependency on {@code java.security.SecureRandom}.
 *
 * <p>The cleartext key returned by {@link #generate()} is surfaced once to the admin
 * caller (in the {@code POST /admin/api-keys} 201 body) and is unrecoverable from the
 * server afterwards — only its BCrypt hash is persisted, via {@link ApiKeyHasher}.
 */
public interface ApiKeyGenerator {

    GeneratedApiKey generate();

    /**
     * Freshly generated API-key material. {@code cleartextApiKey} is the opaque secret
     * that the caller will need to keep — it is NOT a domain value object because the
     * domain never stores it: only the BCrypt hash reaches the {@code ApiKey} aggregate.
     */
    record GeneratedApiKey(ClientId clientId, String cleartextApiKey) {}
}
