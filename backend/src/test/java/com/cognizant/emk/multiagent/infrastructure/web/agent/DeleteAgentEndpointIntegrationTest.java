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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code DELETE /agents/{agentId}} (US-06-008).
 *
 * <p>Verifies the FK cascade fires through the REST DELETE path:
 * {@code agents → conversations → messages}, plus the three child tables
 * ({@code agent_tools}, {@code agent_mcp_servers}, {@code agent_team}). The
 * conversation / message rows are seeded via JDBC because their domain
 * repositories don't exist yet (EPIC-10 / EPIC-11).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DeleteAgentEndpointIntegrationTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ALICE_EMAIL = "alice@example.test";
    private static final String ALICE_PASSWORD = "Standard!1A";
    private static final String BOB_EMAIL = "bob@example.test";
    private static final String BOB_PASSWORD = "Standard!1A";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AgentRepository agentRepository;
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
        aliceId = seedUser(ALICE_EMAIL, ALICE_PASSWORD);
        bobId = seedUser(BOB_EMAIL, BOB_PASSWORD);
    }

    @Test
    void delete_cascades_through_conversations_and_messages() throws Exception {
        AgentId agentId = persistAgentWithToolsMcpTeam(aliceId, "victim");
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversations (id, agent_id, owner_user_id, title, message_count) "
                        + "VALUES (?, ?, ?, ?, ?)",
                conversationId, agentId.value(), aliceId.value(), "t", 1);
        jdbc.update(
                "INSERT INTO messages (id, conversation_id, role, content) "
                        + "VALUES (?, ?, ?, ?)",
                messageId, conversationId, "USER", "hi");

        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(delete("/api/v1/agents/{agentId}", agentId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(countRows("agents", "id", agentId.value())).isEqualTo(0);
        assertThat(countRows("agent_tools", "agent_id", agentId.value())).isEqualTo(0);
        assertThat(countRows("agent_mcp_servers", "agent_id", agentId.value())).isEqualTo(0);
        assertThat(countRowsForParent(agentId.value())).isEqualTo(0);
        assertThat(countRows("conversations", "id", conversationId)).isEqualTo(0);
        assertThat(countRows("messages", "id", messageId)).isEqualTo(0);
    }

    @Test
    void deleting_the_same_id_twice_returns_404_on_the_second_attempt() throws Exception {
        AgentId id = persistAgent(aliceId, "x");
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(delete("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/agents/{agentId}", id.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void cross_owner_DELETE_returns_404_NOT_FOUND_not_403() throws Exception {
        AgentId bobs = persistAgent(bobId, "bobs");
        String aliceToken = login(ALICE_EMAIL, ALICE_PASSWORD);

        mockMvc.perform(delete("/api/v1/agents/{agentId}", bobs.value())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // Bob's agent must still exist.
        assertThat(countRows("agents", "id", bobs.value())).isEqualTo(1);
    }

    @Test
    void unknown_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(ALICE_EMAIL, ALICE_PASSWORD);
        mockMvc.perform(delete("/api/v1/agents/{agentId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anonymous_returns_401_INVALID_CREDENTIALS() throws Exception {
        AgentId id = persistAgent(aliceId, "x");
        mockMvc.perform(delete("/api/v1/agents/{agentId}", id.value()))
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

    /**
     * Seeds an agent + a team-mate it references, plus one tool and one MCP server,
     * so the cascade check covers every side table.
     */
    private AgentId persistAgentWithToolsMcpTeam(UserId owner, String name) {
        AgentId mate = persistAgent(owner, name + "-mate");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of("AwsS3Tool"), List.of("brave-search"),
                new Team(List.of(mate)),
                now, now));
        return id;
    }

    private int countRows(String table, String column, Object value) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
        return n == null ? 0 : n;
    }

    private int countRowsForParent(UUID parentId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM agent_team WHERE parent_agent_id = ?",
                Integer.class, parentId);
        return n == null ? 0 : n;
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
