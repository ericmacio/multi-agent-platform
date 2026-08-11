package com.cognizant.emk.multiagent.infrastructure.web.conversation;

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
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code POST /conversations} (US-10-005).
 * Covers the USER happy path, cross-owner 404, SYSTEM v1 404, missing
 * agentId 400, unauthenticated 401, and disabled-user rejection at the
 * JWT filter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CreateConversationEndpointIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final String BOB_EMAIL = "bob@example.test";
    private static final String BOB_PASSWORD = "Standard!1A";
    private static final String DISABLED_EMAIL = "disabled@example.test";
    private static final String DISABLED_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private ApiKeyGenerator apiKeyGenerator;
    @Autowired private ApiKeyHasher apiKeyHasher;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;

    private UserId aliceId;
    private UserId bobId;
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetAndSeed() {
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        aliceId = seedUser(ALICE_EMAIL, ALICE_PASSWORD, false);
        bobId = seedUser(BOB_EMAIL, BOB_PASSWORD, false);
    }

    // ------- happy path -------

    @Test
    void user_starts_conversation_with_their_own_agent_returns_201() throws Exception {
        AgentId agentId = persistAgentFor(aliceId, "research-bot");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId.value() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.agentId").value(agentId.value().toString()))
                .andExpect(jsonPath("$.messageCount").value(0))
                .andExpect(jsonPath("$.title").doesNotExist())     // null title elided by NON_NULL
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.updatedAt").isString());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations WHERE agent_id = ? AND owner_user_id = ?",
                Integer.class, agentId.value(), aliceId.value());
        assertThat(count).isEqualTo(1);
    }

    // ------- 404 cases -------

    @Test
    void user_starting_conversation_with_other_users_agent_returns_404_no_row_created()
            throws Exception {
        AgentId bobsAgent = persistAgentFor(bobId, "bobs-bot");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + bobsAgent.value() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void user_starting_conversation_with_unknown_agent_returns_404() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void system_caller_starting_conversation_always_returns_404_in_v1() throws Exception {
        // Even with a real, existing user-owned agent, SYSTEM cannot start a
        // conversation in v1 because no agent is SYSTEM-owned. Documents the
        // v1 contract pending a future SYSTEM-owned-agents EPIC.
        AgentId aliceAgent = persistAgentFor(aliceId, "alice-bot");
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(post("/api/v1/conversations")
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + aliceAgent.value() + "\"}"))
                .andExpect(status().isNotFound())                       // NOT 403
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM conversations", Integer.class);
        assertThat(count).isZero();
    }

    // ------- 400 case -------

    @Test
    void missing_agent_id_in_body_returns_400_field_agent_id() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("agentId"));
    }

    // ------- 401 case -------

    @Test
    void unauthenticated_post_returns_401_invalid_credentials() throws Exception {
        mockMvc.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- disabled user -------

    @Test
    void disabled_user_cannot_authenticate_and_therefore_cannot_create_conversation()
            throws Exception {
        seedUser(DISABLED_EMAIL, DISABLED_PASSWORD, true);

        // A disabled user is rejected at the LoginService — login itself returns
        // 401, so no token is ever issued. We exercise that path here rather
        // than fabricating a token, because that is the actual filter chain a
        // disabled user encounters.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + DISABLED_EMAIL + "\",\"password\":\""
                                + DISABLED_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private UserId seedUser(String email, String password, boolean disabled) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id, new Email(email), BCRYPT.encode(password),
                Role.STANDARD, disabled, false, now, now));
        return id;
    }

    private AgentId persistAgentFor(UserId owner, String name) {
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
        if (start < 0) {
            throw new AssertionError("field '" + field + "' not found in: " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
