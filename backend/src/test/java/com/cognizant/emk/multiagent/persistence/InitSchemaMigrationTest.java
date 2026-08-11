package com.cognizant.emk.multiagent.persistence;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@code V001__init_schema.sql} is applied by Flyway and that the schema
 * matches the design contract: every documented table exists, and a check-constraint
 * spot test rejects an invalid value.
 */
class InitSchemaMigrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private static final List<String> EXPECTED_TABLES = List.of(
            "users",
            "agents",
            "agent_tools",
            "agent_mcp_servers",
            "agent_team",
            "conversations",
            "messages",
            "api_keys",
            "jwt_denylist",
            "rate_limit_config"
    );

    @Test
    void flyway_history_records_v001_as_applied() {
        Boolean applied = new JdbcTemplate(dataSource).queryForObject(
                "select exists (select 1 from flyway_schema_history "
                        + "where version = '001' and success = true)",
                Boolean.class);
        assertThat(applied).isTrue();
    }

    @Test
    void every_documented_table_exists() {
        List<String> tables = new JdbcTemplate(dataSource).queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
        assertThat(tables).containsAll(EXPECTED_TABLES);
    }

    @Test
    void messages_role_check_constraint_rejects_tool_role() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Seed a user, agent, conversation so we can attempt a message insert.
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        jdbc.update("insert into users (id, email, password_hash, role) values (?, ?, ?, 'ADMIN')",
                userId, "constraint-probe@example.test", "$2a$10$" + "x".repeat(53));
        jdbc.update("insert into agents (id, owner_id, name, description, system_prompt) "
                        + "values (?, ?, 'a', 'd', 's')",
                agentId, userId);
        // owner_id was replaced by owner_user_id / owner_client_id in V005 (US-10-002).
        // This test exercises the messages_role_check constraint and is run against the
        // post-V005 schema by the PostgresIntegrationTest base — use the new column.
        jdbc.update("insert into conversations (id, agent_id, owner_user_id) values (?, ?, ?)",
                conversationId, agentId, userId);

        assertThatThrownBy(() -> jdbc.update(
                "insert into messages (conversation_id, role, content) values (?, 'TOOL', 'x')",
                conversationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("messages_role_check");
    }
}
