package com.cognizant.emk.multiagent.application.auth;

/**
 * Technical port for one-way hashing and verification of API-key cleartext secrets
 * (design §8.4 / §8.5).
 *
 * <p>Sibling of {@link PasswordHasher} but targets raw strings: an API key is an opaque
 * random secret, not a user-chosen password, so it must NOT travel through the platform
 * password policy enforced by {@code Password}. The shipped adapter in v1 is
 * {@code BcryptApiKeyHasherAdapter} (BCrypt cost factor 10, same as the password
 * hasher).
 */
public interface ApiKeyHasher {

    /** Returns the BCrypt hash of {@code cleartextApiKey}. */
    String hash(String cleartextApiKey);

    /**
     * Returns {@code true} iff {@code cleartextApiKey} was the input that produced
     * {@code storedHash}. Implementations MUST return {@code false} (and not throw)
     * when {@code storedHash} is null, empty, or otherwise malformed.
     */
    boolean matches(String cleartextApiKey, String storedHash);
}
