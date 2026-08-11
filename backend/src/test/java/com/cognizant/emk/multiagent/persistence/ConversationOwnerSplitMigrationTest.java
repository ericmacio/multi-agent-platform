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
 * Verifies that {@code V005__conversation_owner_split.sql} applies cleanly,
 * that the {@code conversations} table now carries the two mutually-exclusive
 * owner columns + the XOR check constraint, and that the per-owner-type
 * partial indexes plus the agent-scoped index exist (US-10-002).
 *
 * <p>Notably this test does NOT roll the schema back to V004 and re-run V005
 * — {@link PostgresIntegrationTest} clean-and-migrates once per class, so we
 * observe the post-V005 state directly. The XOR-violation insert is
 * exercised against the live schema with {@code DELETE FROM conversations}
 * scoped to the rows this test created.
 */
class ConversationOwnerSplitMigrationTest extends PostgresIntegrationTest {

    private static final String SAMPLE_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Autowired private DataSource dataSource;

    @Test
    void flyway_history_records_v005_as_applied() {
        Boolean applied = new JdbcTemplate(dataSource).queryForObject(
                "select exists (select 1 from flyway_schema_history "
                        + "where version = '005' and success = true)",
                Boolean.class);
        assertThat(applied).isTrue();
    }

    @Test
    void legacy_owner_id_column_is_absent() {
        Integer matches = new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'conversations' "
                        + "and column_name = 'owner_id'",
                Integer.class);
        assertThat(matches)
                .as("V005 must drop the legacy owner_id column")
                .isZero();
    }

    @Test
    void new_owner_columns_are_present_and_nullable() {
        List<String> columns = new JdbcTemplate(dataSource).queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'conversations' "
                        + "and column_name in ('owner_user_id', 'owner_client_id') "
                        + "and is_nullable = 'YES'",
                String.class);
        assertThat(columns)
                .as("owner_user_id and owner_client_id must both exist and be nullable")
                .containsExactlyInAnyOrder("owner_user_id", "owner_client_id");
    }

    @Test
    void xor_check_constraint_is_present() {
        Integer count = new JdbcTemplate(dataSource).queryForObject(
                "select count(*) from pg_constraint "
                        + "where conname = 'ck_conversations_owner_xor' and contype = 'c'",
                Integer.class);
        assertThat(count)
                .as("ck_conversations_owner_xor must be defined on conversations")
                .isOne();
    }

    @Test
    void per_owner_partial_indexes_and_agent_index_exist() {
        List<String> indexes = new JdbcTemplate(dataSource).queryForList(
                "select indexname from pg_indexes "
                        + "where schemaname = 'public' and tablename = 'conversations'",
                String.class);
        assertThat(indexes).contains(
                "idx_conversations_user_created",
                "idx_conversations_client_created",
                "idx_conversations_agent_created");
        assertThat(indexes)
                .as("V001's pre-V005 owner index must be gone")
                .doesNotContain("idx_conversations_owner_created");
    }

    @Test
    void inserting_a_row_with_both_owner_columns_violates_the_xor_constraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID userId = newUser(jdbc, "xor-both@example.test");
        UUID agentId = newAgent(jdbc, userId);
        String clientId = newApiKey(jdbc);

        assertThatThrownBy(() -> jdbc.update(
                "insert into conversations (id, agent_id, owner_user_id, owner_client_id) "
                        + "values (?, ?, ?, ?)",
                UUID.randomUUID(), agentId, userId, clientId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_conversations_owner_xor");
    }

    @Test
    void inserting_a_row_with_neither_owner_column_violates_the_xor_constraint() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID userId = newUser(jdbc, "xor-neither@example.test");
        UUID agentId = newAgent(jdbc, userId);

        assertThatThrownBy(() -> jdbc.update(
                "insert into conversations (id, agent_id) values (?, ?)",
                UUID.randomUUID(), agentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_conversations_owner_xor");
    }

    @Test
    void inserting_a_user_owned_row_succeeds() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID userId = newUser(jdbc, "user-owner@example.test");
        UUID agentId = newAgent(jdbc, userId);

        UUID conversationId = UUID.randomUUID();
        jdbc.update(
                "insert into conversations (id, agent_id, owner_user_id) values (?, ?, ?)",
                conversationId, agentId, userId);

        Integer hits = jdbc.queryForObject(
                "select count(*) from conversations "
                        + "where id = ? and owner_user_id = ? and owner_client_id is null",
                Integer.class, conversationId, userId);
        assertThat(hits).isOne();
    }

    @Test
    void inserting_a_system_owned_row_succeeds() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID userId = newUser(jdbc, "system-owner-user@example.test");
        UUID agentId = newAgent(jdbc, userId);
        String clientId = newApiKey(jdbc);

        UUID conversationId = UUID.randomUUID();
        jdbc.update(
                "insert into conversations (id, agent_id, owner_client_id) values (?, ?, ?)",
                conversationId, agentId, clientId);

        Integer hits = jdbc.queryForObject(
                "select count(*) from conversations "
                        + "where id = ? and owner_client_id = ? and owner_user_id is null",
                Integer.class, conversationId, clientId);
        assertThat(hits).isOne();
    }

    // ----- helpers -----

    private static UUID newUser(JdbcTemplate jdbc, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into users (id, email, password_hash, role) values (?, ?, ?, 'STANDARD')",
                id, email, SAMPLE_HASH);
        return id;
    }

    private static UUID newAgent(JdbcTemplate jdbc, UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into agents (id, owner_id, name, description, system_prompt) "
                        + "values (?, ?, ?, 'd', 's')",
                id, ownerId, "a-" + System.nanoTime());
        return id;
    }

    private static String newApiKey(JdbcTemplate jdbc) {
        String clientId = "test-" + System.nanoTime();
        jdbc.update(
                "insert into api_keys (client_id, api_key_hash, label, disabled) "
                        + "values (?, ?, 'probe', false)",
                clientId, SAMPLE_HASH);
        return clientId;
    }
}
