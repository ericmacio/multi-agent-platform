package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link SecureRandom}-backed implementation of {@link ApiKeyGenerator} (design §8.4).
 *
 * <p>{@code clientId}: a fresh {@link UUID#randomUUID()} rendered without dashes
 * (32 lowercase hex characters), small enough to comfortably fit the
 * {@code api_keys.client_id varchar(64)} column and URL-safe so it round-trips through
 * HTTP headers without escaping.
 *
 * <p>{@code cleartextApiKey}: 32 random bytes drawn from the held {@link SecureRandom}
 * instance, base64url-encoded without padding (≈ 43 characters, character set
 * {@code [A-Za-z0-9_-]}). The hash of this cleartext is what the BCrypt-backed
 * {@code ApiKeyHasher} stores — the cleartext itself never reaches the {@code ApiKey}
 * aggregate.
 *
 * <p>The class exposes two constructors so unit tests can pin the random source
 * (seeded {@link SecureRandom}) and assert determinism. The {@code @Autowired} marker
 * on the production constructor disambiguates the two paths for Spring.
 */
@Component
public class SecureRandomApiKeyGeneratorAdapter implements ApiKeyGenerator {

    private static final int SECRET_BYTES = 32;

    private final SecureRandom random;

    // @Autowired is required here because the class has two constructors (the
    // package-private one below is reserved for unit tests); without it, Spring cannot
    // disambiguate. Mirrors the JjwtTokenServiceAdapter convention.
    @Autowired
    public SecureRandomApiKeyGeneratorAdapter() {
        this(new SecureRandom());
    }

    /** Test-friendly constructor; not meant for Spring autowiring. */
    SecureRandomApiKeyGeneratorAdapter(SecureRandom random) {
        this.random = random;
    }

    @Override
    public GeneratedApiKey generate() {
        String clientIdValue = UUID.randomUUID().toString().replace("-", "");
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        String cleartext = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new GeneratedApiKey(new ClientId(clientIdValue), cleartext);
    }
}
