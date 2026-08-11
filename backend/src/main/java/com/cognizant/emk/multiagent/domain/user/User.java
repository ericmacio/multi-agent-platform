package com.cognizant.emk.multiagent.domain.user;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * User aggregate (REQ-USR-001).
 *
 * <p>Holds the BCrypt-hashed password as a raw {@code String} (the {@link Password} value
 * object models cleartext only). All fields are non-null; {@code passwordHash} carries the
 * BCrypt output produced by the infrastructure {@code PasswordHasher} adapter.
 */
public record User(
        UserId id,
        Email email,
        String passwordHash,
        Role role,
        boolean disabled,
        boolean mustChangePassword,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** True when the account is enabled and may authenticate. */
    public boolean isActive() {
        return !disabled;
    }

    /**
     * Returns a copy with the new password hash applied, {@code mustChangePassword}
     * cleared, and {@code updatedAt} bumped to {@code now}. All other fields are preserved.
     */
    public User withNewPasswordHash(String newHash, OffsetDateTime now) {
        Objects.requireNonNull(newHash, "newHash");
        Objects.requireNonNull(now, "now");
        return new User(id, email, newHash, role, disabled, false, createdAt, now);
    }

    /**
     * Returns a copy with {@code disabled} replaced and {@code updatedAt} bumped to
     * {@code now}. All other fields — including the password hash and
     * {@code mustChangePassword} — are preserved. Consumed by the admin enable / disable
     * endpoint (US-05-007).
     */
    public User withDisabled(boolean newDisabled, OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        return new User(id, email, passwordHash, role, newDisabled, mustChangePassword, createdAt, now);
    }
}
