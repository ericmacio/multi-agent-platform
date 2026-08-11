package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.domain.user.Password;

/**
 * Technical port for one-way password hashing and verification (REQ-SEC-002).
 *
 * <p>The application layer depends on this interface so that login and password-change
 * use cases stay framework-free. The single shipped adapter in v1 is BCrypt-backed
 * ({@code BcryptPasswordHasherAdapter}); additional algorithms can be plugged in later
 * without touching use-case code.
 */
public interface PasswordHasher {

    /** Returns the BCrypt hash of the cleartext carried by {@code password}. */
    String hash(Password password);

    /**
     * Returns {@code true} iff {@code rawPassword}'s cleartext was the input that produced
     * {@code storedHash}. Implementations MUST return {@code false} (and not throw) when
     * {@code storedHash} is malformed or otherwise unparseable.
     */
    boolean matches(Password rawPassword, String storedHash);
}
