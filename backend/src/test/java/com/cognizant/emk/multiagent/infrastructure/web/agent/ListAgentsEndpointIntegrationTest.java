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
import java.time.temporal.ChronoUnit;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code GET /agents} (US-06-005).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ListAgentsEndpointIntegrationTest {

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
    void paginates_through_three_alice_agents_and_excludes_bobs() throws Exception {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        persistAgent(aliceId, "alice-a", base);
        persistAgent(aliceId, "alice-b", base.plusSeconds(1));
        persistAgent(aliceId, "alice-c", base.plusSeconds(2));
        persistAgent(bobId, "bobs-bot", base.plusSeconds(3));

        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        String first = mockMvc.perform(get("/api/v1/agents?pageSize=2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("alice-c"))
                .andExpect(jsonPath("$.items[1].name").value("alice-b"))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andExpect(jsonPath("$.pageSize").value(2))
                .andReturn().getResponse().getContentAsString();

        String cursor = extract(first, "nextCursor");
        mockMvc.perform(get("/api/v1/agents?pageSize=2&cursor=" + cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("alice-a"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void empty_owner_returns_empty_items_and_no_next_cursor() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void owner_isolation_bob_never_sees_alice_agents() throws Exception {
        persistAgent(aliceId, "alice-a",
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS));

        String bobToken = login(BOB_EMAIL, BOB_PASSWORD);
        mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void page_size_zero_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/agents?pageSize=0")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void page_size_101_returns_400_VALIDATION_ERROR() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/agents?pageSize=101")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
    }

    @Test
    void garbage_cursor_returns_400_VALIDATION_ERROR_field_cursor() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(get("/api/v1/agents?cursor=not!valid!base64!")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    @Test
    void system_caller_via_api_key_returns_403_FORBIDDEN() throws Exception {
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(get("/api/v1/agents")
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymous_returns_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(get("/api/v1/agents"))
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

    private void persistAgent(UserId owner, String name, OffsetDateTime when) {
        agentRepository.save(new Agent(
                new AgentId(UUID.randomUUID()), owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                when, when));
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
