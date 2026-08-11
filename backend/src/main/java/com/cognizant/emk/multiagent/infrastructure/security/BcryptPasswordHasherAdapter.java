package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.application.auth.PasswordHasher;
import com.cognizant.emk.multiagent.domain.user.Password;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt-backed implementation of {@link PasswordHasher} (design §8.5).
 *
 * <p>Uses the Spring Security {@code BCryptPasswordEncoder} default cost factor 10 — adequate
 * at the v1 scale (REQ-NFR-005) and matches the seed migration's placeholder hash format.
 * The cleartext is read from the {@link Password} value object only when invoking the
 * encoder; it is never stored, returned, or logged (REQ-SEC-002 / REQ-SEC-004).
 */
@Component
public class BcryptPasswordHasherAdapter implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(Password password) {
        return encoder.encode(password.cleartext());
    }

    @Override
    public boolean matches(Password rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        return encoder.matches(rawPassword.cleartext(), storedHash);
    }
}
