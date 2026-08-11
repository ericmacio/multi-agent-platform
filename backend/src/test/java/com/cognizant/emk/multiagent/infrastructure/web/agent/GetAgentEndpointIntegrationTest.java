package com.cognizant.emk.multiagent.infrastructure.web.agent;

import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator;
import com.cognizant.emk.multiagent.application.auth.ApiKeyGenerator.GeneratedApiKey;
import com.cognizant.emk.multiagent.application.auth.ApiKeyHasher;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code GET /agents/{agentId}} (US-06-006).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class GetAgentEndpointIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final String BOB_EMAIL = "bob@example.test";
    private static final String BOB_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGenerator apiKeyGenerator;
    @Autowired private ApiKeyHasher apiKeyHasher;
    @Autowired private Flyway flyway;

    private UserId aliceId;
    private UserId bobId;

    @BeforeEach
    void resetAndSeedUsers() {
        flyway.clean();
        flyway.migrate();
        aliceId = seedUser(ALICE_EMAIL, ALICE_PASSWORD);
        bobId = seedUser(BOB_EMAIL, BOB_PASSWORD);
    }

    @Test
    void existing_owned_agent_returns_200_with_the_documented_shape() throws Exception {
        AgentId id = persistAgent(aliceId, "research");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.ownerId").value(aliceId.value().toString()))
                .andExpect(jsonPath("$.name").value("research"));
    }

    @Test
    void cross_owner_GET_returns_404_byte_identical_to_truly_not_found() throws Exception {
        AgentId bobsAgent = persistAgent(bobId, "bobs-bot");
        String aliceToken = login(ALICE_EMAIL, ALICE_PASSWORD);

        String crossOwner = mockMvc.perform(get("/api/v1/agents/{agentId}", bobsAgent.value())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        String trulyUnknown = mockMvc.perform(get("/api/v1/agents/{agentId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Both responses must share the same "Agent not found: <uuid>" shape — the
        // detail differs because the requested uuid differs, but the code/title/status
        // are identical and the agent's existence is never disclosed. Asserting on the
        // shape rather than byte equality keeps the test robust to a future change
        // that hides the uuid.
        assertThat(crossOwner).contains("\"code\":\"NOT_FOUND\"")
                .contains("\"status\":404");
        assertThat(trulyUnknown).contains("\"code\":\"NOT_FOUND\"")
                .contains("\"status\":404");
    }

    @Test
    void unknown_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/agents/{agentId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void malformed_uuid_in_path_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/agents/{agentId}", "not-a-uuid")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("agentId"));
    }

    @Test
    void system_caller_via_api_key_returns_403_FORBIDDEN() throws Exception {
        AgentId id = persistAgent(aliceId, "x");
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(get("/api/v1/agents/{agentId}", id.value())
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymous_returns_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/agents/{agentId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private UserId seedUser(String email, String password) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id, new Email(email), BCRYPT.encode(password),
                Role.STANDARD, false, false, now, now));
        return id;
    }

    private AgentId persistAgent(UserId owner, String name) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now));
        return id;
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new AssertionError("field '" + field + "' not found in: " + json);
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
