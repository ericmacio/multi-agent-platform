package com.cognizant.emk.multiagent.application.auth;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case for the {@code POST /auth/logout} endpoint (REQ-AUTH-011).
 *
 * <p>Records the presented JWT's {@code jti} on the {@link JwtDenylist} until at least the
 * token's natural {@code exp}. Subsequent requests using the same token are rejected by the
 * {@code JwtAuthenticationFilter}. The denylist entry self-evicts no later than the token's
 * natural expiry so the denylist remains bounded by the configured JWT lifetime.
 */
public interface LogoutUseCase {

    /** Idempotent: calling twice with the same {@code jti} is a no-op. */
    void logout(LogoutCommand command);

    /** Inputs to {@link #logout(LogoutCommand)}. */
    record LogoutCommand(UUID jti, OffsetDateTime expiresAt) {

        public LogoutCommand {
            Objects.requireNonNull(jti, "jti");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
