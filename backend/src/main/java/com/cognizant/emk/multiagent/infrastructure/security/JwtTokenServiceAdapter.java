package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.application.auth.JwtTokenService;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JJWT-backed implementation of {@link JwtTokenService} (design §8.2).
 *
 * <p>Signs and verifies HS256 tokens with the secret loaded into
 * {@link ApplicationProperties}. Bean-validation on the properties record enforces the
 * 32-byte minimum at startup (REQ-AUTH-010); a defensive check in this constructor catches
 * the same condition for unit tests that bypass Spring binding.
 *
 * <p>Time is read from the Spring-managed {@link Clock} bean (US-CR1-003): both
 * {@link #issue} (for {@code iat}/{@code exp}) and {@link #verify} (for the JJWT parser's
 * expiry check) honor the same virtualized "now" as
 * {@code InMemoryJwtDenylistAdapter}. Production wiring uses {@code Clock.systemUTC()}
 * via {@code ClockConfig}; tests can swap in a {@code MutableClock} via {@code @Primary}.
 *
 * <p>Sensitive material is never logged: not the token, not the secret, not the cleartext
 * claims (REQ-SEC-004). Callers that need the {@code jti} for diagnostics can read it from
 * the returned {@link IssuedToken} or {@link TokenClaims}.
 */
@Component
public class JwtTokenServiceAdapter implements JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenServiceAdapter.class);

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_UID = "uid";
    private static final int MIN_SECRET_BYTES = 32; // HS256 requires ≥ 256 bits of key material.

    private final SecretKey signingKey;
    private final Duration lifetime;
    private final Clock clock;

    // @Autowired is required here because the class has two constructors (the
    // package-private one below is reserved for unit tests); without it, Spring cannot
    // disambiguate. This is the documented exception to the "no @Autowired on the sole
    // constructor" project convention.
    @Autowired
    public JwtTokenServiceAdapter(ApplicationProperties properties, Clock clock) {
        this(
                properties.security().jwt().signingSecret(),
                properties.security().jwt().lifetime(),
                clock);
    }

    /** Test-friendly constructor; not meant for Spring autowiring. */
    JwtTokenServiceAdapter(String signingSecret, Duration lifetime, Clock clock) {
        if (signingSecret == null || signingSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT signing secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8));
        this.lifetime = lifetime;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(User user) {
        log.info("Generate token for user {}", user.email());
        UUID jti = UUID.randomUUID();
        Instant now = clock.instant();
        Instant exp = now.plus(lifetime);
        String token = Jwts.builder()
                .subject(user.email().value())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_UID, user.id().value().toString())
                .id(jti.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, jti, exp.atOffset(ZoneOffset.UTC));
    }

    @Override
    public TokenClaims verify(String rawToken) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    // Bridge java.time.Clock to io.jsonwebtoken.Clock so the parser's
                    // expiry check observes the same virtualized "now" as issue().
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(rawToken)
                    .getPayload();

            UUID userId = UUID.fromString(requireString(claims.get(CLAIM_UID, String.class), CLAIM_UID));
            Email email = new Email(claims.getSubject());
            Role role = Role.valueOf(requireString(claims.get(CLAIM_ROLE, String.class), CLAIM_ROLE));
            UUID jti = UUID.fromString(requireString(claims.getId(), "jti"));
            Date expiration = claims.getExpiration();
            if (expiration == null) {
                throw new IllegalArgumentException("missing exp claim");
            }
            return new TokenClaims(
                    new UserId(userId),
                    email,
                    role,
                    jti,
                    expiration.toInstant().atOffset(ZoneOffset.UTC));
        } catch (RuntimeException ex) {
            // Domain-side: caller sees the static "Authentication failed." message; the cause
            // is attached so the GlobalExceptionHandler's debug logging can carry detail.
            throw new InvalidCredentialsException(ex);
        }
    }

    private static String requireString(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing or blank claim: " + name);
        }
        return value;
    }
}
