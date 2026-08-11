package com.cognizant.emk.multiagent.infrastructure.web.observability;

import com.cognizant.emk.multiagent.persistence.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * DOWN-path companion to {@link ActuatorHealthEndpointIntegrationTest}.
 *
 * <p>Registers a {@link HealthIndicator} bean that always reports {@code DOWN};
 * Spring Boot's composite health resolution pulls the overall status to DOWN
 * (any DOWN contributor → composite DOWN → HTTP 503). The body remains
 * {@code {"status":"DOWN"}} because {@code show-details=never} prevents the
 * contributor identity from leaking — same REQ-SEC-004 stance as the UP path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(ActuatorHealthDownWhenDataSourceFailsTest.ForceDownHealthConfig.class)
class ActuatorHealthDownWhenDataSourceFailsTest extends PostgresIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired private MockMvc mockMvc;

    @Test
    void health_endpoint_returns_503_DOWN_when_a_contributor_is_down() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();

        // A composite DOWN status surfaces as 503 Service Unavailable.
        assertThat(result.getResponse().getStatus()).isEqualTo(503);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(body.get("status").asText()).isEqualTo("DOWN");
        // show-details=never still suppresses contributor details on DOWN.
        assertThat(body.has("components")).isFalse();
        assertThat(body.has("details")).isFalse();
    }

    @TestConfiguration
    static class ForceDownHealthConfig {

        @Bean
        HealthIndicator forceDown() {
            return () -> Health.down().build();
        }
    }
}
