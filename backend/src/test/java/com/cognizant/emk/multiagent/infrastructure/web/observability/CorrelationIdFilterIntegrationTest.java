package com.cognizant.emk.multiagent.infrastructure.web.observability;

import com.cognizant.emk.multiagent.domain.ratelimit.RateLimitConfig;
import com.cognizant.emk.multiagent.infrastructure.ratelimit.Bucket4jRateLimitGate;
import com.cognizant.emk.multiagent.persistence.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * End-to-end pin for the EPIC-15 / US-15-002 correlation-ID contract.
 *
 * <p>Boots the full security chain so the load-bearing invariants —
 * filter ordering ({@link CorrelationIdFilter} BEFORE
 * {@code RateLimitFilter}), the response-header echo, and the CORS
 * expose-headers list — are all exercised against production wiring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CorrelationIdFilterIntegrationTest extends PostgresIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    @Autowired private MockMvc mockMvc;
    @Autowired private Bucket4jRateLimitGate gate;

    @BeforeEach
    void resetBucket() {
        // Generous bucket so the per-test setup does not throttle the request
        // flow before the per-test assertion. Individual tests that need a
        // tight bucket rebuild it themselves below.
        gate.onRateLimitConfigChanged(new RateLimitConfig(
                1000, 10000, OffsetDateTime.now(ZoneOffset.UTC), Optional.empty()));
    }

    @Test
    void absent_inbound_header_generates_uuid_v4_response_header() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/_rl_probe")).andReturn();

        String emitted = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(emitted).isNotNull();
        assertThat(UUID_V4.matcher(emitted).matches())
                .as("response header should be a UUID v4 when no inbound header is supplied")
                .isTrue();
    }

    @Test
    void wellformed_inbound_header_is_echoed_verbatim() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/_rl_probe")
                        .header("X-Correlation-Id", "my-trace-001"))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isEqualTo("my-trace-001");
    }

    @Test
    void malformed_inbound_header_is_silently_regenerated() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/_rl_probe")
                        .header("X-Correlation-Id", "value with spaces !@#"))
                .andReturn();

        String emitted = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(emitted).isNotNull();
        assertThat(emitted).isNotEqualTo("value with spaces !@#");
        assertThat(UUID_V4.matcher(emitted).matches()).isTrue();
    }

    @Test
    void rate_limited_response_carries_correlation_id() throws Exception {
        // Tighten to a 1-token bucket so the SECOND request lands in the
        // bucket-denied branch — RateLimitFilter rejects via 429, but
        // CorrelationIdFilter runs BEFORE RateLimitFilter so the response
        // still carries the header (US-15-002 load-bearing invariant).
        gate.onRateLimitConfigChanged(new RateLimitConfig(
                1, 9999, OffsetDateTime.now(ZoneOffset.UTC), Optional.empty()));

        // First request: consumes the token; succeeds (401 because anonymous,
        // which is fine — we're asserting on the header, not the status).
        mockMvc.perform(get("/api/v1/_rl_probe")).andReturn();

        // Second request: bucket exhausted → 429 with the standard envelope.
        MvcResult denied = mockMvc.perform(get("/api/v1/_rl_probe")).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(429);

        String emitted = denied.getResponse().getHeader("X-Correlation-Id");
        assertThat(emitted).isNotNull();
        assertThat(UUID_V4.matcher(emitted).matches()).isTrue();
    }

    @Test
    void cors_preflight_includes_correlation_id_in_expose_and_allow_headers() throws Exception {
        MvcResult result = mockMvc.perform(options("/api/v1/_rl_probe")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Correlation-Id"))
                .andReturn();

        String allow = result.getResponse().getHeader("Access-Control-Allow-Headers");
        assertThat(allow).isNotNull();
        assertThat(allow).contains("X-Correlation-Id");
    }

    @Test
    void simple_get_from_allowed_origin_exposes_correlation_id_to_browser() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/_rl_probe")
                        .header("Origin", ALLOWED_ORIGIN))
                .andReturn();

        String expose = result.getResponse().getHeader("Access-Control-Expose-Headers");
        assertThat(expose).isNotNull();
        assertThat(expose).contains("Retry-After");
        assertThat(expose).contains("X-Correlation-Id");
    }

    @Test
    void each_request_gets_its_own_correlation_id() throws Exception {
        MvcResult r1 = mockMvc.perform(get("/api/v1/_rl_probe")).andReturn();
        MvcResult r2 = mockMvc.perform(get("/api/v1/_rl_probe")).andReturn();

        String v1 = r1.getResponse().getHeader("X-Correlation-Id");
        String v2 = r2.getResponse().getHeader("X-Correlation-Id");

        assertThat(v1).isNotNull();
        assertThat(v2).isNotNull();
        assertThat(v1)
                .as("server-generated correlation IDs MUST differ between requests — Tomcat thread reuse must not leak MDC values")
                .isNotEqualTo(v2);
    }
}
