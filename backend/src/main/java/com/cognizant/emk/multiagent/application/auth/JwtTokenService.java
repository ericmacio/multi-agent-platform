package com.cognizant.emk.multiagent.application.auth;

import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Technical port for HS256 JWT issuance and verification (REQ-AUTH-002 / -003 / -010).
 *
 * <p>Sits behind the application/auth boundary so the {@code LoginUseCase} and the
 * {@code JwtAuthenticationFilter} stay free of the JJWT library. The shipped adapter in v1
 * is {@code JjwtTokenServiceAdapter}.
 */
public interface JwtTokenService {

    /**
     * Issues a fresh signed JWT for {@code user}. Claims emitted: {@code sub} (email),
     * {@code role}, {@code uid} (user id UUID), {@code jti} (fresh UUID), {@code iat},
     * {@code exp = iat + configured lifetime}.
     */
    IssuedToken issue(User user);

    /**
     * Validates {@code rawToken}'s signature, expiry, and claim shape. Returns a
     * {@link TokenClaims} on success. Throws {@link InvalidCredentialsException} on any
     * failure (bad signature, expired, malformed, missing/invalid claim, unknown role)
     * with the underlying cause attached for the logger but never surfaced to clients.
     */
    TokenClaims verify(String rawToken);

    /** Token issued by {@link #issue(User)}. */
    record IssuedToken(String token, UUID jti, OffsetDateTime expiresAt) {}

    /** Verified claims extracted by {@link #verify(String)}. */
    record TokenClaims(UserId userId, Email email, Role role, UUID jti, OffsetDateTime expiresAt) {}
}
