package com.cognizant.emk.multiagent.infrastructure.security;

import com.cognizant.emk.multiagent.application.auth.JwtTokenService.IssuedToken;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService.TokenClaims;
import com.cognizant.emk.multiagent.domain.auth.InvalidCredentialsException;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-Java unit test for {@link JwtTokenServiceAdapter}: builds the adapter with the
 * package-private constructor and exercises every contracted branch without a Spring
 * context.
 */
class JwtTokenServiceAdapterTest {

    private static final String SECRET = "test-only-jwt-signing-secret-with-at-least-thirty-two-bytes!";
    private static final String OTHER_SECRET = "DIFFERENT-jwt-signing-secret-with-at-least-thirty-two-bytes!";
    private static final Duration LIFETIME = Duration.ofMinutes(30);

    private final JwtTokenServiceAdapter adapter =
            new JwtTokenServiceAdapter(SECRET, LIFETIME, Clock.systemUTC());

    private static User newUser() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new User(
                new UserId(UUID.randomUUID()),
                new Email("alice@example.test"),
                "irrelevant-hash",
                Role.STANDARD,
                false,
                false,
                now,
                now);
    }

    @Test
    void rejects_construction_with_a_too_short_secret() {
        assertThatThrownBy(() ->
                new JwtTokenServiceAdapter("short-secret", LIFETIME, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void issue_then_verify_round_trips_all_claims() {
        User user = newUser();
        IssuedToken issued = adapter.issue(user);

        TokenClaims claims = adapter.verify(issued.token());

        assertThat(claims.userId()).isEqualTo(user.id());
        assertThat(claims.email()).isEqualTo(user.email());
        assertThat(claims.role()).isEqualTo(user.role());
        assertThat(claims.jti()).isEqualTo(issued.jti());
        // Issued.expiresAt and verified.expiresAt should be the same point in time
        // (allowing a 1-second tolerance for ms-truncation in the JWT exp claim).
        long deltaSec = Math.abs(claims.expiresAt().toEpochSecond() - issued.expiresAt().toEpochSecond());
        assertThat(deltaSec).isLessThanOrEqualTo(1);
        // expiresAt is approximately now + lifetime.
        long lifetimeSec = LIFETIME.toSeconds();
        long actualLifetimeSec = claims.expiresAt().toEpochSecond() - Instant.now().getEpochSecond();
        assertThat(actualLifetimeSec).isBetween(lifetimeSec - 2, lifetimeSec + 2);
    }

    @Test
    void verify_rejects_a_token_signed_with_a_different_key() {
        IssuedToken issued = adapter.issue(newUser());

        JwtTokenServiceAdapter foreign =
                new JwtTokenServiceAdapter(OTHER_SECRET, LIFETIME, Clock.systemUTC());

        assertThatThrownBy(() -> foreign.verify(issued.token()))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Authentication failed.");
    }

    @Test
    void issue_at_T_then_verify_at_T_plus_lifetime_plus_one_second_is_rejected() {
        // Round-trip the expiry branch entirely through the injected Clock — no need to
        // forge a token with a hand-rolled past `exp`. Both issue() and verify() consult
        // the same MutableClock (US-CR1-003).
        MutableClock mutable = new MutableClock(Instant.parse("2026-05-05T12:00:00Z"));
        JwtTokenServiceAdapter virtual = new JwtTokenServiceAdapter(SECRET, LIFETIME, mutable);

        IssuedToken issued = virtual.issue(newUser());
        // Still valid right after issuance.
        TokenClaims fresh = virtual.verify(issued.token());
        assertThat(fresh.jti()).isEqualTo(issued.jti());

        mutable.advance(LIFETIME.plusSeconds(1));

        assertThatThrownBy(() -> virtual.verify(issued.token()))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Authentication failed.");
    }

    @Test
    void verify_rejects_an_expired_token() {
        // Forge an already-expired token signed with the same key the adapter uses.
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        String expired = Jwts.builder()
                .subject("bob@example.test")
                .claim("role", "STANDARD")
                .claim("uid", UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(past.minus(Duration.ofMinutes(30))))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> adapter.verify(expired))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Authentication failed.");
    }

    @Test
    void verify_rejects_a_token_with_role_outside_the_enum() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String roguelyRoled = Jwts.builder()
                .subject("eve@example.test")
                .claim("role", "HACKER")
                .claim("uid", UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(LIFETIME)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> adapter.verify(roguelyRoled))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Authentication failed.");
    }

    @Test
    void verify_rejects_a_malformed_token() {
        assertThatThrownBy(() -> adapter.verify("not.a.jwt"))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> adapter.verify("nonsense"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void verify_rejects_a_token_missing_the_uid_claim() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String missingUid = Jwts.builder()
                .subject("eve@example.test")
                .claim("role", "STANDARD")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(LIFETIME)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> adapter.verify(missingUid))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
