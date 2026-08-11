package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt-backed implementation of {@link ApiKeyHasher} (design §8.4 / §8.5).
 *
 * <p>Uses the Spring Security {@code BCryptPasswordEncoder} default cost factor 10 —
 * the same factor as {@code BcryptPasswordHasherAdapter}, sufficient at the v1 scale
 * (REQ-NFR-005) and matching the {@code varchar(72)} {@code api_keys.api_key_hash}
 * column. The cleartext is never stored, returned, or logged
 * (REQ-SEC-002 / REQ-SEC-004).
 */
@Component
public class BcryptApiKeyHasherAdapter implements ApiKeyHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String cleartextApiKey) {
        return encoder.encode(cleartextApiKey);
    }

    @Override
    public boolean matches(String cleartextApiKey, String storedHash) {
        if (cleartextApiKey == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        try {
            return encoder.matches(cleartextApiKey, storedHash);
        } catch (IllegalArgumentException malformedHash) {
            // BCryptPasswordEncoder.matches throws on a hash that does not match the
            // BCrypt shape. Per the port contract, swallow and return false rather than
            // surfacing a technical exception.
            return false;
        }
    }
}
