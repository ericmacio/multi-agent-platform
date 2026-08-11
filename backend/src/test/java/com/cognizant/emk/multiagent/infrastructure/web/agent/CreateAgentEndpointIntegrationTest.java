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
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code POST /agents} (US-06-004).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CreateAgentEndpointIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String STANDARD_EMAIL = "alice@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";
    private static final String OTHER_EMAIL = "bob@example.test";
    private static final String OTHER_PASSWORD = "Standard!1A";

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

    @BeforeEach
    void resetAndSeedUsers() {
        flyway.clean();
        flyway.migrate();
        aliceId = seedUser(STANDARD_EMAIL, STANDARD_PASSWORD);
        bobId = seedUser(OTHER_EMAIL, OTHER_PASSWORD);
    }

    // ------- happy path -------

    @Test
    void standard_user_creates_an_agent_under_their_own_ownership() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"research-bot","description":"Searches the web",
                                 "systemPrompt":"You are a research bot.","memorySize":24,
                                 "llmModel":"gpt-4o-mini","temperature":0.7,
                                 "maxOutputTokens":1024,"topP":0.95,
                                 "tools":["AwsS3Tool"],"enabledMcpServers":["brave-search"],
                                 "team":[]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.ownerId").value(aliceId.value().toString()))
                .andExpect(jsonPath("$.name").value("research-bot"))
                .andExpect(jsonPath("$.memorySize").value(24))
                .andExpect(jsonPath("$.llmModel").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.tools[0]").value("AwsS3Tool"))
                .andExpect(jsonPath("$.enabledMcpServers[0]").value("brave-search"))
                .andExpect(jsonPath("$.team").isArray());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agents WHERE owner_id = ?", Integer.class, aliceId.value());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void omitted_memory_size_falls_back_to_default_12() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memorySize").value(12));
    }

    // ------- 409 cases -------

    @Test
    void duplicate_name_same_owner_returns_409_DUPLICATE_AGENT_NAME() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alpha\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alpha\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_AGENT_NAME"));
    }

    @Test
    void same_name_different_owner_is_allowed() throws Exception {
        String aliceToken = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        String bobToken = login(OTHER_EMAIL, OTHER_PASSWORD);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shared\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shared\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void team_member_owned_by_another_user_returns_409_CROSS_OWNER_TEAM_MEMBER() throws Exception {
        AgentId bobsAgent = persistAgentFor(bobId, "bobs-bot");
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alice-bot\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"team\":[\"" + bobsAgent.value() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CROSS_OWNER_TEAM_MEMBER"));
    }

    @Test
    void team_member_with_non_empty_team_returns_409_NESTED_TEAM_FORBIDDEN() throws Exception {
        // Alice creates two agents: a leaf and a parent referencing the leaf.
        AgentId leaf = persistAgentFor(aliceId, "leaf");
        AgentId parent = persistAgentForWithTeam(aliceId, "parent", List.of(leaf));
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);

        // Now attempt to create an agent whose team contains `parent` — which itself
        // has a non-empty team. Single-level rule: rejected.
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"grandparent\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"team\":[\"" + parent.value() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NESTED_TEAM_FORBIDDEN"));
    }

    // ------- 400 cases -------

    @Test
    void name_over_32_chars_returns_400_field_name() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        String over = "a".repeat(33);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + over + "\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("name")));
    }

    @Test
    void memory_size_zero_returns_400_field_memory_size() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\",\"memorySize\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("memorySize"));
    }

    @Test
    void memory_size_37_returns_400_field_memory_size() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\",\"memorySize\":37}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("memorySize"));
    }

    @Test
    void description_over_1024_returns_400_field_description() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        String over = "a".repeat(1025);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"" + over + "\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.hasItem("description")));
    }

    @Test
    void unknown_tool_name_returns_400_field_tools() throws Exception {
        // US-07-005: the catalog-backed validator rejects tool names not in the
        // static catalog. The known-good "AwsS3Tool" happy path is exercised above.
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"tools\":[\"does-not-exist\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("tools"));
    }

    @Test
    void unknown_mcp_server_name_returns_400_field_enabledMcpServers() throws Exception {
        // US-08-006: catalog-backed MCP validator rejects names not in the configured
        // catalog. The known-good "brave-search" happy path is exercised above.
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"enabledMcpServers\":[\"does-not-exist\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("enabledMcpServers"));
    }

    @Test
    void mixed_known_and_unknown_mcp_server_names_returns_400() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"enabledMcpServers\":[\"brave-search\",\"does-not-exist\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("enabledMcpServers"));
    }

    @Test
    void filesystem_mcp_server_is_accepted() throws Exception {
        String token = login(STANDARD_EMAIL, STANDARD_PASSWORD);
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"fs-bot\",\"description\":\"d\",\"systemPrompt\":\"s\","
                                + "\"enabledMcpServers\":[\"filesystem\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabledMcpServers[0]").value("filesystem"));
    }

    // ------- authorization -------

    @Test
    void system_caller_via_api_key_returns_403_FORBIDDEN() throws Exception {
        GeneratedApiKey generated = apiKeyGenerator.generate();
        apiKeyRepository.save(new ApiKey(
                generated.clientId(),
                apiKeyHasher.hash(generated.cleartextApiKey()),
                "ci", false, OffsetDateTime.now(ZoneOffset.UTC)));

        mockMvc.perform(post("/api/v1/agents")
                        .header("X-Client-Id", generated.clientId().value())
                        .header("X-Api-Key", generated.cleartextApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void anonymous_returns_401_INVALID_CREDENTIALS() throws Exception {
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"description\":\"d\",\"systemPrompt\":\"s\"}"))
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

    private AgentId persistAgentForWithTeam(UserId owner, String name, List<AgentId> team) {
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
     * and {@code filesystem}. The test profile excludes Spring AI's MCP autoconfig
     * (so {@code McpStdioClientProperties} is otherwise absent) and never spawns
     * {@code npx} subprocesses.
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
