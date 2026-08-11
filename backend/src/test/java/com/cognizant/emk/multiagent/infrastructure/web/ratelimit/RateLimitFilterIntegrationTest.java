package com.cognizant.emk.multiagent.infrastructure.web.ratelimit;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfigRepository;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import com.cognizant.emk.multiagent.infrastructure.ratelimit.Bucket4jRateLimitGate;
import io.github.bucket4j.TimeMeter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end regression test for EPIC-13. Boots the full Spring Security chain
 * with the <i>real</i> {@code RateLimitFilter}, the <i>real</i>
 * {@code Bucket4jRateLimitGate}, and the <i>real</i>
 * {@code GlobalExceptionHandler}. Only the {@link TimeMeter} is virtualized so
 * per-minute / per-hour boundaries can be exercised without {@code Thread.sleep}.
 *
 * <p>Scenarios pinned here:
 * <ol>
 *   <li>per-minute boundary eviction (allowed/denied + Retry-After bound);</li>
 *   <li>per-hour boundary eviction;</li>
 *   <li>429 response envelope matches the openapi {@code RateLimited} example;</li>
 *   <li>unauthenticated traffic counts toward the bucket (filter is outermost);</li>
 *   <li>live admin {@code PUT /admin/rate-limit} takes effect on the next request;</li>
 *   <li>{@code /actuator/health} is excluded via {@code shouldNotFilter}.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(RateLimitFilterIntegrationTest.VirtualClockConfig.class)
class RateLimitFilterIntegrationTest {

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
    @Autowired private RateLimitConfigRepository repository;
    @Autowired private VirtualTimeMeter clock;

    @BeforeEach
    void resetSchemaClearAdminFlagAndBucket() {
        flyway.clean();
        flyway.migrate();
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
        clock.reset();
        // Rebuild the bucket from the freshly-seeded row so every test starts with
        // a full bucket of the size it has configured via tightenBucket(...).
        gate.onRateLimitConfigChanged(repository.load());
    }

    // ------- (1) per-minute boundary eviction -------

    @Test
    void per_minute_boundary_eviction() throws Exception {
        // Authenticate FIRST so the login call's bucket consumption does not eat into
        // the tightened budget. Tightening rebuilds the bucket full at the new size.
        String token = login();
        tightenBucket(3, 999);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(probe(token)).andExpect(status().isOk());
        }
        mockMvc.perform(probe(token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(retryAfterBetween(1, 60));

        clock.advanceSeconds(60);
        mockMvc.perform(probe(token)).andExpect(status().isOk());
    }

    // ------- (2) per-hour boundary eviction -------

    @Test
    void per_hour_boundary_eviction() throws Exception {
        String token = login();
        tightenBucket(999, 2);

        mockMvc.perform(probe(token)).andExpect(status().isOk());
        mockMvc.perform(probe(token)).andExpect(status().isOk());
        mockMvc.perform(probe(token))
                .andExpect(status().isTooManyRequests())
                .andExpect(retryAfterBetween(1, 3600));
    }

    // ------- (3) 429 envelope matches openapi RateLimited example -------

    @Test
    void rate_limited_response_envelope_matches_openapi() throws Exception {
        String token = login();
        tightenBucket(1, 999);

        mockMvc.perform(probe(token)).andExpect(status().isOk());
        mockMvc.perform(probe(token))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Global rate limit exceeded; retry later."))
                .andExpect(jsonPath("$.type").value("https://errors.multi-agent-platform/rate-limited"))
                .andExpect(jsonPath("$.instance").value("/api/v1/_rl_probe"))
                .andExpect(header().exists("Retry-After"));
    }

    // ------- (4) unauthenticated traffic also counts -------

    @Test
    void unauthenticated_traffic_counts_toward_the_bucket() throws Exception {
        tightenBucket(1, 999);

        // Burn the single token via an unauthenticated request. The filter runs BEFORE
        // JwtAuthenticationFilter, so the bucket is consulted regardless of credentials.
        mockMvc.perform(get("/api/v1/_rl_probe"));

        // The very next call — even with no Authorization header — must see 429,
        // proving the filter is outermost (REQ-RL-003).
        mockMvc.perform(get("/api/v1/_rl_probe"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ------- (5) live admin PUT takes effect on the next request -------

    @Test
    void live_admin_update_takes_effect_on_the_next_request() throws Exception {
        String token = login();
        // Tighten via the public REST surface so the listener wiring (the one the
        // production code actually walks on every admin PUT) is exercised end-to-end.
        // Bucket starts at (10, 50); the PUT below shrinks it to (2, 999) and the
        // listener atomically rebuilds the bucket fresh-full at the new size.
        mockMvc.perform(put("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perMinute\":2,\"perHour\":999}"))
                .andExpect(status().isOk());

        // Exhaust the freshly-rebuilt (2, 999) bucket via the probe.
        mockMvc.perform(probe(token)).andExpect(status().isOk());
        mockMvc.perform(probe(token)).andExpect(status().isOk());
        mockMvc.perform(probe(token)).andExpect(status().isTooManyRequests());

        // Advance the virtual clock by 60s so per-minute refills enough to let the
        // loosen-PUT itself through (it consumes one token entering the filter, before
        // the listener rebuild fires). Per-hour budget (999) has plenty of room.
        clock.advanceSeconds(60);

        // Live admin PUT — listener (Bucket4jRateLimitGate) rebuilds the bucket fresh
        // at the new (100, 1000) ceiling. The next 3 probe requests must see 200 —
        // proving the new ceiling is in force; the OLD (2, 999) limit would have
        // denied the third one.
        mockMvc.perform(put("/api/v1/admin/rate-limit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perMinute\":100,\"perHour\":1000}"))
                .andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(probe(token)).andExpect(status().isOk());
        }
    }

    // ------- (6) actuator excluded -------

    @Test
    void actuator_health_is_excluded_from_the_bucket() throws Exception {
        tightenBucket(1, 999);

        // Exhaust the bucket on unauth probes — burns the single token without needing JWT.
        mockMvc.perform(get("/api/v1/_rl_probe"));
        mockMvc.perform(get("/api/v1/_rl_probe")).andExpect(status().isTooManyRequests());

        // /actuator/health bypasses the filter via shouldNotFilter. The actuator
        // module itself is added by EPIC-15 (still Draft today), so the endpoint
        // returns 404 here — but the load-bearing assertion is that the response
        // is NOT 429: if the rate-limit filter had run, we'd see RATE_LIMITED
        // because the bucket is exhausted.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isNotEqualTo(429));
    }

    // ------- helpers -------

    /**
     * Tighten the bucket via the listener seam without going through the REST PUT
     * (which would itself consume tokens). Uses the repository's save → gate
     * onRateLimitConfigChanged callback wiring directly.
     */
    private void tightenBucket(int perMinute, int perHour) {
        gate.onRateLimitConfigChanged(new com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig(
                perMinute, perHour,
                OffsetDateTime.now(ZoneOffset.UTC),
                java.util.Optional.empty()));
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
    }

    private static org.springframework.test.web.servlet.RequestBuilder probe(String token) {
        return get("/api/v1/_rl_probe").header("Authorization", "Bearer " + token);
    }

    private static org.springframework.test.web.servlet.ResultMatcher retryAfterBetween(int min, int max) {
        return result -> {
            String header = result.getResponse().getHeader("Retry-After");
            int seconds = Integer.parseInt(header);
            assertThat(seconds).isBetween(min, max);
        };
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

    // ------- virtual-clock test wiring -------

    /**
     * Replaces the production {@link Bucket4jRateLimitGate} bean with one that
     * uses a virtualized {@link TimeMeter} so per-minute / per-hour boundaries
     * can be driven without {@code Thread.sleep}. {@code @Primary} wins over
     * the default Spring-component-scanned bean.
     */
    @TestConfiguration
    static class VirtualClockConfig {

        @Bean
        VirtualTimeMeter virtualTimeMeter() {
            return new VirtualTimeMeter();
        }

        @Bean
        @Primary
        Bucket4jRateLimitGate virtualClockBucket4jRateLimitGate(
                RateLimitConfigRepository repository, VirtualTimeMeter clock) {
            return Bucket4jRateLimitGate.withCustomTimeMeter(repository, clock);
        }
    }

    /** Public so the {@code @Bean} method above can return it without visibility tweaks. */
    public static final class VirtualTimeMeter implements TimeMeter {

        private final AtomicLong nanos = new AtomicLong(0L);

        public void advanceSeconds(long seconds) {
            nanos.addAndGet(seconds * 1_000_000_000L);
        }

        public void reset() {
            nanos.set(0L);
        }

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }
}
