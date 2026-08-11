package com.cognizant.emk.multiagent.infrastructure.web.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code PUT /agents/{agentId}} (US-06-007).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class UpdateAgentEndpointIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final String BOB_EMAIL = "bob@example.test";
    private static final String BOB_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private Flyway flyway;

    private UserId aliceId;
    private UserId bobId;

    @BeforeEach
    void resetAndSeed() {
        flyway.clean();
        flyway.migrate();
        aliceId = seedUser(ALICE_EMAIL, ALICE_PASSWORD);
        bobId = seedUser(BOB_EMAIL, BOB_PASSWORD);
    }

    @Test
    void happy_path_replaces_every_field_and_bumps_updated_at() throws Exception {
        AgentId id = persistAgent(aliceId, "old-name");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"new-name","description":"new desc","systemPrompt":"new prompt",
                                 "memorySize":18,"llmModel":"gpt-4o","temperature":0.4,
                                 "maxOutputTokens":512,"topP":0.9,"tools":["AwsS3Tool"],
                                 "enabledMcpServers":["brave-search"],"team":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.ownerId").value(aliceId.value().toString()))
                .andExpect(jsonPath("$.name").value("new-name"))
                .andExpect(jsonPath("$.memorySize").value(18))
                .andExpect(jsonPath("$.llmModel").value("gpt-4o"));
    }

    @Test
    void rename_to_the_same_name_is_allowed_and_returns_200() throws Exception {
        AgentId id = persistAgent(aliceId, "alpha");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alpha\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rename_to_another_alice_agents_name_returns_409_DUPLICATE_AGENT_NAME() throws Exception {
        persistAgent(aliceId, "alpha");
        AgentId beta = persistAgent(aliceId, "beta");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", beta.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alpha\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_AGENT_NAME"));
    }

    @Test
    void rename_to_a_name_used_by_another_owner_is_allowed() throws Exception {
        persistAgent(bobId, "shared");
        AgentId alice = persistAgent(aliceId, "alpha");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", alice.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shared\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void team_self_reference_returns_409_NESTED_TEAM_FORBIDDEN() throws Exception {
        AgentId id = persistAgent(aliceId, "self");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"self\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"team\":[\"" + id.value() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NESTED_TEAM_FORBIDDEN"));
    }

    @Test
    void team_member_from_another_owner_returns_409_CROSS_OWNER_TEAM_MEMBER() throws Exception {
        AgentId alice = persistAgent(aliceId, "alice");
        AgentId bobs = persistAgent(bobId, "bobs-bot");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", alice.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alice\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"team\":[\"" + bobs.value() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CROSS_OWNER_TEAM_MEMBER"));
    }

    @Test
    void team_member_with_non_empty_team_returns_409_NESTED_TEAM_FORBIDDEN() throws Exception {
        AgentId leaf = persistAgent(aliceId, "leaf");
        AgentId parent = persistAgentWithTeam(aliceId, "parent", List.of(leaf));
        AgentId grandparent = persistAgent(aliceId, "gp");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", grandparent.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"gp\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"team\":[\"" + parent.value() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NESTED_TEAM_FORBIDDEN"));
    }

    @Test
    void cross_owner_PUT_returns_404_NOT_FOUND_not_403() throws Exception {
        AgentId bobs = persistAgent(bobId, "bobs-bot");
        String aliceToken = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", bobs.value())
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unknown_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(put("/api/v1/agents/{agentId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anonymous_returns_401_INVALID_CREDENTIALS() throws Exception {
        AgentId id = persistAgent(aliceId, "x");
        mockMvc.perform(put("/api/v1/agents/{agentId}", id.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unknown_mcp_server_name_returns_400_field_enabledMcpServers() throws Exception {
        // US-08-006 — symmetric assertion to CreateAgentEndpointIntegrationTest.
        AgentId id = persistAgent(aliceId, "x");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"enabledMcpServers\":[\"does-not-exist\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("enabledMcpServers"));
    }

    @Test
    void filesystem_mcp_server_is_accepted_on_update() throws Exception {
        AgentId id = persistAgent(aliceId, "x");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(put("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"enabledMcpServers\":[\"filesystem\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabledMcpServers[0]").value("filesystem"));
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
        return persistAgentWithTeam(owner, name, List.of());
    }

    private AgentId persistAgentWithTeam(UserId owner, String name, List<AgentId> team) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), new Team(team),
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

    /**
     * Seeds the MCP catalog with the two preconfigured production names so the
     * catalog-backed reference validator (US-08-006) recognizes {@code brave-search}
     * and {@code filesystem}.
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        McpStdioClientProperties testMcpStdioClientProperties() {
            McpStdioClientProperties properties = new McpStdioClientProperties();
            properties.getConnections().put("brave-search", inertParameters());
            properties.getConnections().put("filesystem", inertParameters());
            return properties;
        }

        private static McpStdioClientProperties.Parameters inertParameters() {
            return new McpStdioClientProperties.Parameters("noop", List.of(), Map.of());
        }
    }
}
