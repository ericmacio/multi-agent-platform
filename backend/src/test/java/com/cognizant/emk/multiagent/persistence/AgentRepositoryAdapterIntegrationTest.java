package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end JPA integration test for {@link AgentRepository}: aggregate
 * round-trip (parent + 3 child tables), owner-scoped keyset paging, the
 * repository-backed invariants ({@code existsByOwnerAndName},
 * {@code existsByOwnerAndNameExcludingId}, {@code findOwnerOf},
 * {@code hasNonEmptyTeam}), and the FK cascade through the REST-less DELETE
 * path (US-06-008's REST integration test covers the controller-driven flow).
 */
class AgentRepositoryAdapterIntegrationTest extends PostgresIntegrationTest {

    private static final String SAMPLE_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Autowired private AgentRepository agentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UserId ownerA;
    private UserId ownerB;

    @BeforeEach
    void resetGraph() {
        jdbc = new JdbcTemplate(dataSource);
        // Clear every owner-derived row; the schema FK cascade does most of the work.
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM agent_team");
        jdbc.update("DELETE FROM agent_mcp_servers");
        jdbc.update("DELETE FROM agent_tools");
        jdbc.update("DELETE FROM agents");
        jdbc.update("DELETE FROM users WHERE email <> 'bootstrap@example.test'");

        ownerA = saveUser("owner-a@example.test");
        ownerB = saveUser("owner-b@example.test");
    }

    // ------- round trip -------

    @Test
    void save_then_find_by_id_round_trips_every_field_including_child_rows() {
        AgentId id = new AgentId(UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId memberId = persistTeamMemberFor(ownerA);

        Agent toSave = new Agent(
                id,
                ownerA,
                new AgentName("research-bot"),
                "Searches the web",
                "You are a research bot.",
                new MemorySize(24),
                new SamplingParams("gpt-4o-mini", 0.7, 1024, 0.95),
                List.of("AwsS3Tool", "Calculator"),
                List.of("brave-search", "filesystem"),
                new Team(List.of(memberId)),
                now, now);

        Agent saved = agentRepository.save(toSave);
        Agent loaded = agentRepository.findById(id).orElseThrow();

        assertThat(saved.id()).isEqualTo(id);
        assertThat(loaded.id()).isEqualTo(id);
        assertThat(loaded.ownerId()).isEqualTo(ownerA);
        assertThat(loaded.name()).isEqualTo(new AgentName("research-bot"));
        assertThat(loaded.description()).isEqualTo("Searches the web");
        assertThat(loaded.systemPrompt()).isEqualTo("You are a research bot.");
        assertThat(loaded.memorySize().value()).isEqualTo(24);
        assertThat(loaded.samplingParams().llmModel()).isEqualTo("gpt-4o-mini");
        assertThat(loaded.samplingParams().temperature()).isEqualTo(0.7);
        assertThat(loaded.samplingParams().maxOutputTokens()).isEqualTo(1024);
        assertThat(loaded.samplingParams().topP()).isEqualTo(0.95);
        // Child rows: order is the deterministic ORDER BY in the @Query (tool_name asc).
        assertThat(loaded.tools()).containsExactly("AwsS3Tool", "Calculator");
        assertThat(loaded.enabledMcpServers()).containsExactly("brave-search", "filesystem");
        assertThat(loaded.team().members()).extracting(AgentId::value)
                .containsExactly(memberId.value());
    }

    @Test
    void save_replaces_child_rows_wholesale_on_subsequent_save() {
        AgentId id = new AgentId(UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        AgentId memberId1 = persistTeamMemberFor(ownerA);
        AgentId memberId2 = persistTeamMemberFor(ownerA);
        agentRepository.save(new Agent(
                id, ownerA, new AgentName("agent-1"),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of("tool-1", "tool-2"), List.of("mcp-1"),
                new Team(List.of(memberId1, memberId2)),
                now, now));

        // Re-save with a completely different shape — only one tool, no MCP, one
        // team member.
        agentRepository.save(new Agent(
                id, ownerA, new AgentName("agent-1"),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of("tool-replaced"), List.of(),
                new Team(List.of(memberId1)),
                now, now.plusSeconds(1)));

        Agent loaded = agentRepository.findById(id).orElseThrow();
        assertThat(loaded.tools()).containsExactly("tool-replaced");
        assertThat(loaded.enabledMcpServers()).isEmpty();
        assertThat(loaded.team().members()).extracting(AgentId::value)
                .containsExactly(memberId1.value());
        // And: the side tables in DB have exactly the new row counts — no leftovers.
        assertThat(countRows("agent_tools", id.value())).isEqualTo(1);
        assertThat(countRows("agent_mcp_servers", id.value())).isEqualTo(0);
        assertThat(countRowsForParent(id.value())).isEqualTo(1);
    }

    // ------- listByOwner -------

    @Test
    void list_by_owner_returns_only_owner_rows_in_descending_created_at_order() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId a1 = persistAgentFor(ownerA, "a-1", base);
        AgentId a2 = persistAgentFor(ownerA, "a-2", base.plusSeconds(1));
        AgentId a3 = persistAgentFor(ownerA, "a-3", base.plusSeconds(2));
        persistAgentFor(ownerB, "b-1", base.plusSeconds(3)); // newer than every A but other owner

        Page<Agent> page = agentRepository.listByOwner(ownerA, null, 10);
        assertThat(page.items()).extracting(Agent::id)
                .containsExactly(a3, a2, a1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void list_by_owner_paginates_keyset_style_across_two_pages() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId a1 = persistAgentFor(ownerA, "a-1", base);
        AgentId a2 = persistAgentFor(ownerA, "a-2", base.plusSeconds(1));
        AgentId a3 = persistAgentFor(ownerA, "a-3", base.plusSeconds(2));

        Page<Agent> first = agentRepository.listByOwner(ownerA, null, 2);
        assertThat(first.items()).extracting(Agent::id).containsExactly(a3, a2);
        assertThat(first.nextCursor()).isNotNull();

        Page<Agent> second = agentRepository.listByOwner(ownerA, first.nextCursor(), 2);
        assertThat(second.items()).extracting(Agent::id).containsExactly(a1);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void list_by_owner_with_no_rows_returns_empty_page_with_null_cursor() {
        Page<Agent> page = agentRepository.listByOwner(ownerA, null, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    // ------- invariants -------

    @Test
    void exists_by_owner_and_name_is_true_for_persisted_pair_and_false_for_other_owner() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        persistAgentFor(ownerA, "shared", now);

        assertThat(agentRepository.existsByOwnerAndName(ownerA, new AgentName("shared"))).isTrue();
        // Same name owned by a different user is not a conflict — REQ-AGT-002.
        assertThat(agentRepository.existsByOwnerAndName(ownerB, new AgentName("shared"))).isFalse();
        assertThat(agentRepository.existsByOwnerAndName(ownerA, new AgentName("nope"))).isFalse();
    }

    @Test
    void exists_by_owner_and_name_excluding_id_ignores_the_supplied_agent() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId selfId = persistAgentFor(ownerA, "renamed", now);

        // The same name owned by the same user, but excluding the persisted row → false.
        assertThat(agentRepository.existsByOwnerAndNameExcludingId(
                ownerA, new AgentName("renamed"), selfId)).isFalse();
        // Excluding a different id → still true (the row is unrelated).
        assertThat(agentRepository.existsByOwnerAndNameExcludingId(
                ownerA, new AgentName("renamed"), new AgentId(UUID.randomUUID()))).isTrue();
    }

    @Test
    void find_owner_of_returns_the_owner_id_or_empty() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId id = persistAgentFor(ownerA, "agent", now);

        assertThat(agentRepository.findOwnerOf(id)).hasValue(ownerA);
        assertThat(agentRepository.findOwnerOf(new AgentId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void has_non_empty_team_reflects_the_persisted_state() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId parent = new AgentId(UUID.randomUUID());
        AgentId member = persistTeamMemberFor(ownerA);
        agentRepository.save(new Agent(
                parent, ownerA, new AgentName("parent"),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), new Team(List.of(member)),
                now, now));

        assertThat(agentRepository.hasNonEmptyTeam(parent)).isTrue();
        assertThat(agentRepository.hasNonEmptyTeam(member)).isFalse();
    }

    // ------- delete -------

    @Test
    void delete_removes_the_agent_and_cascades_through_conversations_and_messages() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId agentId = persistAgentFor(ownerA, "victim", now);

        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversations (id, agent_id, owner_user_id, title, message_count) "
                        + "VALUES (?, ?, ?, ?, ?)",
                conversationId, agentId.value(), ownerA.value(), "t", 1);
        jdbc.update(
                "INSERT INTO messages (id, conversation_id, role, content) "
                        + "VALUES (?, ?, ?, ?)",
                messageId, conversationId, "USER", "hi");

        agentRepository.delete(agentId);

        assertThat(agentRepository.findById(agentId)).isEmpty();
        assertThat(rowExists("agents", "id", agentId.value())).isFalse();
        assertThat(rowExists("conversations", "id", conversationId)).isFalse();
        assertThat(rowExists("messages", "id", messageId)).isFalse();
    }

    @Test
    void delete_of_unknown_id_is_a_silent_no_op() {
        agentRepository.delete(new AgentId(UUID.randomUUID()));
        // Sanity: existing rows are untouched.
    }

    // ------- helpers -------

    private UserId saveUser(String email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id, new Email(email), SAMPLE_HASH, Role.STANDARD,
                false, false, now, now));
        return id;
    }

    private AgentId persistAgentFor(UserId owner, String name, OffsetDateTime createdAt) {
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                createdAt, createdAt));
        return id;
    }

    private AgentId persistTeamMemberFor(UserId owner) {
        return persistAgentFor(
                owner,
                "member-" + System.nanoTime(),
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS));
    }

    private int countRows(String table, UUID agentId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE agent_id = ?",
                Integer.class, agentId);
        return n == null ? 0 : n;
    }

    private int countRowsForParent(UUID parentId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM agent_team WHERE parent_agent_id = ?",
                Integer.class, parentId);
        return n == null ? 0 : n;
    }

    private boolean rowExists(String table, String column, Object value) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
        return n != null && n > 0;
    }
}
