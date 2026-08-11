package com.cognizant.emk.multiagent.infrastructure.web.admin;

import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test for {@code DELETE /admin/users/{userId}} (US-05-008).
 *
 * <p>The most load-bearing assertion: the FK cascade chain (users → agents →
 * conversations → messages) fires through the REST DELETE path so REQ-USR-006 is met
 * end-to-end. EPIC-06 / EPIC-10 will introduce domain repositories for agents,
 * conversations, and messages; until then, the test seeds those rows directly via
 * JDBC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DeleteUserEndpointIntegrationTest {

    private static final String ADMIN_EMAIL = "bootstrap@example.test";
    private static final String ADMIN_PASSWORD = "Bootstrap!1A";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final String ADMIN_PASSWORD_HASH = BCRYPT.encode(ADMIN_PASSWORD);

    private static final String STANDARD_EMAIL = "alice@example.test";
    private static final String STANDARD_PASSWORD = "Standard!1A";

    @DynamicPropertySource
    static void overrideBootstrapHash(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.flyway.placeholders.app_bootstrap_admin_password_hash",
                () -> ADMIN_PASSWORD_HASH);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;
    @Autowired private Flyway flyway;

    private JdbcTemplate jdbc;

    @BeforeEach
    void resetSchemaAndClearAdminFlag() {
        flyway.clean();
        flyway.migrate();
        User admin = userRepository.findByEmail(new Email(ADMIN_EMAIL)).orElseThrow();
        userRepository.save(admin.withNewPasswordHash(
                admin.passwordHash(), OffsetDateTime.now(ZoneOffset.UTC)));
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void delete_cascades_through_agents_conversations_and_messages() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        UserId userId = seedStandardUser();
        UUID agentId = insertAgent(userId.value());
        UUID conversationId = insertConversation(agentId, userId.value());
        UUID messageId = insertMessage(conversationId);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", userId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // The cascade chain (users → agents → conversations → messages) must have
        // removed every row referenced from the deleted user.
        assertThat(countRows("users", "id", userId.value())).isEqualTo(0);
        assertThat(countRows("agents", "id", agentId)).isEqualTo(0);
        assertThat(countRows("conversations", "id", conversationId)).isEqualTo(0);
        assertThat(countRows("messages", "id", messageId)).isEqualTo(0);
    }

    @Test
    void deleting_the_same_id_twice_returns_404_on_the_second_attempt() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        UserId userId = seedStandardUser();

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", userId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", userId.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unknown_id_returns_404_NOT_FOUND() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void standard_user_jwt_is_rejected_with_403_FORBIDDEN() throws Exception {
        UserId target = seedStandardUser();
        // Seed a second STANDARD user so login as a non-admin can succeed and target the first.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        userRepository.save(new User(
                new UserId(UUID.randomUUID()),
                new Email("bob@example.test"),
                BCRYPT.encode(STANDARD_PASSWORD),
                Role.STANDARD,
                false,
                false,
                now,
                now));
        String token = login("bob@example.test", STANDARD_PASSWORD);

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", target.value())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // Target row is untouched.
        assertThat(countRows("users", "id", target.value())).isEqualTo(1);
    }

    @Test
    void anonymous_request_is_rejected_with_401_INVALID_CREDENTIALS() throws Exception {
        UserId target = seedStandardUser();

        mockMvc.perform(delete("/api/v1/admin/users/{userId}", target.value()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------- helpers -------

    private UserId seedStandardUser() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id,
                new Email(STANDARD_EMAIL),
                BCRYPT.encode(STANDARD_PASSWORD),
                Role.STANDARD,
                false,
                false,
                now,
                now));
        return id;
    }

    /** Inserts an agent row owned by the given user. EPIC-06 has not yet introduced
     *  the {@code AgentRepository} adapter, so we go straight to JDBC. */
    private UUID insertAgent(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO agents (id, owner_id, name, description, system_prompt, memory_size) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, ownerId, "test-agent", "test description", "you are a test agent", 12);
        return id;
    }

    private UUID insertConversation(UUID agentId, UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversations (id, agent_id, owner_user_id, title, message_count) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id, agentId, ownerId, "test conversation", 1);
        return id;
    }

    private UUID insertMessage(UUID conversationId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO messages (id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
                id, conversationId, "USER", "hello");
        return id;
    }

    private int countRows(String table, String column, Object value) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
        return count == null ? 0 : count;
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
