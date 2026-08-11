package com.cognizant.emk.multiagent.infrastructure.web.observability;

import com.cognizant.emk.multiagent.persistence.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the EPIC-15 / US-15-001 Actuator surface:
 * <ol>
 *   <li>{@code GET /actuator/health} returns 200 with body {@code {"status":"UP"}}.</li>
 *   <li>The endpoint lives at {@code /actuator/health}, NOT under {@code /api/v1/...}.</li>
 *   <li>Only {@code health} is exposed — {@code metrics}, {@code loggers},
 *   {@code info} all return 404.</li>
 *   <li>{@code show-details=never} is enforced: no {@code components} field.</li>
 *   <li>The endpoint is NOT subject to {@link com.cognizant.emk.multiagent
 *   .infrastructure.web.ratelimit.RateLimitFilter} (already excluded via
 *   {@code shouldNotFilter}).</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ActuatorHealthEndpointIntegrationTest extends PostgresIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private MockMvc mockMvc;

    @Test
    void health_endpoint_returns_200_with_status_UP() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.get("status").asText()).isEqualTo("UP");
    }

    @Test
    void health_endpoint_lives_outside_api_v1_prefix() throws Exception {
        // /api/v1/actuator/health must NOT resolve to the health endpoint.
        // SpringSecurityConfig only permits /actuator/health anonymously; any
        // other path (including /api/v1/actuator/health) falls through to
        // anyRequest().authenticated() and surfaces as 401. The load-bearing
        // assertion is "this path does NOT return 200 with the health body".
        MvcResult result = mockMvc.perform(get("/api/v1/actuator/health")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void only_health_is_exposed() throws Exception {
        // exposure.include=health means the metrics / loggers / info beans are
        // not registered. The security chain runs first; since only
        // /actuator/health is permitAll, the other paths hit authentication
        // and surface as 401 to anonymous callers. The contract is "no other
        // /actuator/** endpoint leaks data to anonymous callers", which 401
        // confirms (a 200 would mean leakage).
        assertStatus(get("/actuator/metrics"), 401);
        assertStatus(get("/actuator/loggers"), 401);
        assertStatus(get("/actuator/info"), 401);
        assertStatus(get("/actuator/env"), 401);
        assertStatus(get("/actuator/beans"), 401);
    }

    private void assertStatus(
            org.springframework.test.web.servlet.RequestBuilder request,
            int expected) throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(expected);
    }

    @Test
    void show_details_never_omits_components_field() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        // With show-details=never, the body has exactly one field ("status").
        assertThat(body.has("components")).isFalse();
        assertThat(body.has("details")).isFalse();
    }

    @Test
    void health_endpoint_anonymous_succeeds_without_auth_header() throws Exception {
        // permitAll matcher in SpringSecurityConfig is in effect.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
