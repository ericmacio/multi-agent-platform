package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.application.auth.JwtDenylist;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService;
import com.cognizant.emk.multiagent.application.auth.JwtTokenService.IssuedToken;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@link JwtAuthenticationFilter} and the EPIC-03
 * security chain. Uses the dev-profile {@link MeProbeController} as a target endpoint
 * that surfaces the authenticated principal so the test can assert the JWT filter wired
 * the {@code SecurityContext} correctly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class JwtAuthenticationFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtDenylist jwtDenylist;

    @Value("${app.security.jwt.signing-secret}")
    private String signingSecret;

    private User user;

    @BeforeEach
    void seedSyntheticUser() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        user = new User(
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
    void anonymous_request_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void valid_token_authenticates_and_exposes_the_principal() throws Exception {
        IssuedToken issued = jwtTokenService.issue(user);

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + issued.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().value().toString()))
                .andExpect(jsonPath("$.email").value("alice@example.test"))
                .andExpect(jsonPath("$.role").value("STANDARD"));
    }

    @Test
    void denylisted_token_is_rejected_with_401() throws Exception {
        IssuedToken issued = jwtTokenService.issue(user);
        jwtDenylist.add(issued.jti(), issued.expiresAt());

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + issued.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void expired_token_is_rejected_with_401() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(signingSecret.getBytes(StandardCharsets.UTF_8));
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        String expired = Jwts.builder()
                .subject(user.email().value())
                .claim("role", user.role().name())
                .claim("uid", user.id().value().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(past.minus(Duration.ofMinutes(30))))
                .expiration(Date.from(past))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void tampered_token_is_rejected_with_401() throws Exception {
        IssuedToken issued = jwtTokenService.issue(user);
        // Flip the last char of the signature to break it.
        String token = issued.token();
        char last = token.charAt(token.length() - 1);
        char flipped = last == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + flipped;

        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void non_bearer_authorization_header_is_rejected_with_401() throws Exception {
        mockMvc.perform(get("/api/v1/__test/me")
                        .header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
}
