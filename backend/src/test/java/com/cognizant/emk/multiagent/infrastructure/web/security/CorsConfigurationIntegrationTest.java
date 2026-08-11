package com.cognizant.emk.multiagent.infrastructure.web.security;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.ratelimit.Bucket4jRateLimitGate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the CORS configuration contract for the API (US-14-004).
 *
 * <p>The configuration lives in
 * {@code SpringSecurityConfig.corsConfigurationSource()} and is driven by
 * {@code app.cors.allowed-origins}. The test overrides the property to a single
 * known-good origin via {@link TestPropertySource} and exercises:
 * <ol>
 *   <li>preflight succeeds for the allowed origin with the documented header set;</li>
 *   <li>preflight is rejected (no Allow-Origin header) for a disallowed origin;</li>
 *   <li>a simple GET from the allowed origin echoes the Origin back;</li>
 *   <li>{@code Retry-After} on a 429 carries the CORS allow-origin and
 *       {@code Access-Control-Expose-Headers: Retry-After} so the browser can
 *       read it;</li>
 *   <li>preflight requests with an allowed origin succeed even when the bucket
 *       is exhausted (CorsFilter short-circuits OPTIONS preflight before the
 *       rate-limit filter runs);</li>
 *   <li>{@code X-Client-Id} and {@code X-Api-Key} pass the preflight
 *       allow-headers check.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:5173")
class CorsConfigurationIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String DISALLOWED_ORIGIN = "https://evil.example";
    private static final String ADMIN_EMAIL = "bootstrap@example.test";
    private static final String ADMIN_PASSWORD = "Bootstrap!1A";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_PASSWORD_HASH = BCRYPT.encode(ADMIN_PASSWORD);

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> ADMIN_PASSWORD_HASH);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private Flyway flyway;
    @Autowired private Bucket4jRateLimitGate gate;

    @BeforeEach
    void resetSchemaAndBucket() {
        flyway.clean();
        flyway.migrate();
        // Reseed mustChangePassword=false so login() succeeds without forcing
        // the password-change flow.
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
        // Rebuild the bucket fresh-full from the seeded (10, 50) config.
        gate.onRateLimitConfigChanged(new RateLimitConfig(
                10, 50, OffsetDateTime.now(ZoneOffset.UTC), Optional.empty()));
    }

    // ------- (1) preflight succeeds for an allowed origin -------

    @Test
    void preflight_succeeds_for_allowed_origin() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/agents")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().longValue("Access-Control-Max-Age", 3600L))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("preflight status should be 200 or 204")
                .isIn(200, 204);

        String allowMethods = result.getResponse().getHeader("Access-Control-Allow-Methods");
        assertThat(allowMethods).contains("POST");

        String allowHeaders = result.getResponse().getHeader("Access-Control-Allow-Headers");
        assertThat(allowHeaders).contains("Authorization");
        assertThat(allowHeaders).contains("Content-Type");
    }

    // ------- (2) preflight rejected for a disallowed origin -------

    @Test
    void preflight_rejected_for_disallowed_origin() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/agents")
                        .header("Origin", DISALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andReturn();

        // The CorsFilter MUST NOT include the Allow-Origin response header on a
        // disallowed origin; without it the browser blocks the actual request,
        // which is exactly the behavior we want.
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                .as("disallowed origin must not echo Allow-Origin header")
                .isNull();
    }

    // ------- (3) actual GET with an allowed origin -------

    @Test
    void actual_GET_with_allowed_origin_echoes_origin_header() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/v1/_rl_probe")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    // ------- (4) Retry-After reachable on 429 (Expose-Headers contains it) -------

    @Test
    void rate_limited_response_carries_cors_origin_and_exposes_retry_after()
            throws Exception {
        String token = login();
        // Tighten the bucket to (1, 999); the listener atomically rebuilds the
        // bucket fresh-full at this size.
        gate.onRateLimitConfigChanged(new RateLimitConfig(
                1, 999, OffsetDateTime.now(ZoneOffset.UTC), Optional.empty()));

        // Burn the single token on the probe.
        mockMvc.perform(get("/api/v1/_rl_probe")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/_rl_probe")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andReturn();

        String exposed = result.getResponse().getHeader("Access-Control-Expose-Headers");
        assertThat(exposed)
                .as("Retry-After must be exposed so the browser can read it")
                .isNotNull()
                .contains("Retry-After");
    }

    // ------- (5) preflight not blocked by an exhausted bucket -------

    @Test
    void preflight_succeeds_even_when_bucket_is_exhausted() throws Exception {
        // Tighten to a 1-token bucket and burn it via the probe.
        gate.onRateLimitConfigChanged(new RateLimitConfig(
                1, 999, OffsetDateTime.now(ZoneOffset.UTC), Optional.empty()));
        mockMvc.perform(get("/api/v1/_rl_probe").header("Origin", ALLOWED_ORIGIN));

        // Preflight with the allowed origin SHOULD still see the Allow-Origin
        // header — Spring's CorsFilter handles OPTIONS preflight before the
        // RateLimitFilter would deny it.
        MvcResult result = mockMvc.perform(options("/api/v1/_rl_probe")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andReturn();

        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                .as("preflight must remain reachable so the browser can issue the actual request")
                .isEqualTo(ALLOWED_ORIGIN);
        // The status MUST NOT be 429 — the browser interprets a 429 on preflight
        // as a CORS failure, hiding the cause from the frontend.
        assertThat(result.getResponse().getStatus())
                .as("preflight must not be rate-limited")
                .isNotEqualTo(429);
    }

    // ------- (6) X-Client-Id + X-Api-Key allowed in preflight -------

    @Test
    void preflight_allows_X_Client_Id_and_X_Api_Key_request_headers() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/conversations")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Client-Id, X-Api-Key"))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andReturn();

        String allowed = result.getResponse().getHeader("Access-Control-Allow-Headers");
        assertThat(allowed)
                .as("X-Client-Id and X-Api-Key are part of the API contract (REQ-AUTH-001)")
                .isNotNull()
                .containsIgnoringCase("X-Client-Id")
                .containsIgnoringCase("X-Api-Key");
    }

    // ------- helpers -------

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\""
                                + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("field '" + field + "' not found in: " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
