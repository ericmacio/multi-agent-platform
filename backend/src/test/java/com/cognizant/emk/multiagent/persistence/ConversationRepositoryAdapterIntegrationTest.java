package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.auth.ApiKey;
import com.cognizant.emk.multiagent.domain.auth.ApiKeyRepository;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.conversation.MessageId;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
import com.cognizant.emk.multiagent.domain.conversation.Title;
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
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end JPA integration test for {@link ConversationRepository}:
 * round-trips USER- and SYSTEM-owned conversations, walks the keyset-paged
 * owner-scoped listing with and without the agent filter, exercises the
 * messages append/list/findLastN trio, and verifies the FK cascade through
 * the REST-less DELETE path.
 */
class ConversationRepositoryAdapterIntegrationTest extends PostgresIntegrationTest {

    private static final String SAMPLE_HASH =
            "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    @Autowired private ConversationRepository conversationRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;
    private UserId ownerA;
    private UserId ownerB;
    private AgentId agentA1;
    private AgentId agentA2;
    private AgentId agentB1;
    private ClientId clientPrincipal;

    @BeforeEach
    void resetGraph() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM agent_team");
        jdbc.update("DELETE FROM agent_mcp_servers");
        jdbc.update("DELETE FROM agent_tools");
        jdbc.update("DELETE FROM agents");
        jdbc.update("DELETE FROM api_keys");
        jdbc.update("DELETE FROM users WHERE email <> 'bootstrap@example.test'");

        ownerA = saveUser("conv-owner-a@example.test");
        ownerB = saveUser("conv-owner-b@example.test");
        agentA1 = saveAgent(ownerA, "agent-a1");
        agentA2 = saveAgent(ownerA, "agent-a2");
        agentB1 = saveAgent(ownerB, "agent-b1");
        clientPrincipal = saveApiKey("svc-ci");
    }

    // ----- round trip -----

    @Test
    void save_then_find_by_id_round_trips_a_user_owned_conversation() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId id = new ConversationId(UUID.randomUUID());
        Conversation toSave = new Conversation(
                id,
                agentA1,
                new ConversationOwner.UserOwner(ownerA),
                new Title("planning session"),
                new MessageCount(3),
                now, now);

        Conversation saved = conversationRepository.save(toSave);
        Conversation loaded = conversationRepository.findById(id).orElseThrow();

        assertThat(saved.id()).isEqualTo(id);
        assertThat(loaded.id()).isEqualTo(id);
        assertThat(loaded.agentId()).isEqualTo(agentA1);
        assertThat(loaded.owner())
                .isEqualTo(new ConversationOwner.UserOwner(ownerA));
        assertThat(loaded.title()).isEqualTo(new Title("planning session"));
        assertThat(loaded.messageCount().value()).isEqualTo(3);

        // Schema-level: only owner_user_id is populated.
        assertThat(stringFor(id, "owner_user_id"))
                .as("owner_user_id must be populated")
                .isEqualTo(ownerA.value().toString());
        assertThat(stringFor(id, "owner_client_id"))
                .as("owner_client_id must be null for USER-owned rows")
                .isNull();
    }

    @Test
    void save_then_find_by_id_round_trips_a_system_owned_conversation() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId id = new ConversationId(UUID.randomUUID());
        Conversation toSave = new Conversation(
                id,
                agentA1,
                new ConversationOwner.SystemOwner(clientPrincipal),
                null,
                MessageCount.EMPTY,
                now, now);

        conversationRepository.save(toSave);
        Conversation loaded = conversationRepository.findById(id).orElseThrow();

        assertThat(loaded.owner())
                .isEqualTo(new ConversationOwner.SystemOwner(clientPrincipal));
        assertThat(loaded.title()).isNull();
        assertThat(loaded.messageCount()).isEqualTo(MessageCount.EMPTY);

        // Schema-level: only owner_client_id is populated.
        assertThat(stringFor(id, "owner_user_id")).isNull();
        assertThat(stringFor(id, "owner_client_id")).isEqualTo("svc-ci");
    }

    @Test
    void save_with_existing_id_updates_mutable_fields_in_place() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId id = new ConversationId(UUID.randomUUID());

        conversationRepository.save(new Conversation(
                id, agentA1, new ConversationOwner.UserOwner(ownerA),
                null, MessageCount.EMPTY, base, base));

        // Replay with a new title, bumped count, advanced updatedAt.
        OffsetDateTime later = base.plusMinutes(5);
        conversationRepository.save(new Conversation(
                id, agentA1, new ConversationOwner.UserOwner(ownerA),
                new Title("renamed"), new MessageCount(4), base, later));

        Conversation loaded = conversationRepository.findById(id).orElseThrow();
        assertThat(loaded.title()).isEqualTo(new Title("renamed"));
        assertThat(loaded.messageCount().value()).isEqualTo(4);
        assertThat(loaded.updatedAt()).isEqualTo(later);
    }

    @Test
    void find_by_id_returns_empty_for_unknown_id() {
        assertThat(conversationRepository.findById(new ConversationId(UUID.randomUUID())))
                .isEmpty();
    }

    // ----- listByOwner -----

    @Test
    void list_by_user_owner_returns_only_owner_rows_in_descending_order() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId c1 = persistConv(agentA1, ownerOf(ownerA), base);
        ConversationId c2 = persistConv(agentA1, ownerOf(ownerA), base.plusSeconds(1));
        ConversationId c3 = persistConv(agentA2, ownerOf(ownerA), base.plusSeconds(2));
        persistConv(agentB1, ownerOf(ownerB), base.plusSeconds(3)); // other owner — must not leak

        Page<Conversation> page = conversationRepository.listByOwner(
                new ConversationOwner.UserOwner(ownerA), Optional.empty(), null, 10);

        assertThat(page.items())
                .extracting(Conversation::id)
                .containsExactly(c3, c2, c1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void list_by_user_owner_filters_by_agent_when_provided() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId cA1 = persistConv(agentA1, ownerOf(ownerA), base);
        persistConv(agentA2, ownerOf(ownerA), base.plusSeconds(1));
        ConversationId cA1b = persistConv(agentA1, ownerOf(ownerA), base.plusSeconds(2));

        Page<Conversation> page = conversationRepository.listByOwner(
                new ConversationOwner.UserOwner(ownerA),
                Optional.of(agentA1),
                null,
                10);

        assertThat(page.items())
                .extracting(Conversation::id)
                .containsExactly(cA1b, cA1);
    }

    @Test
    void list_by_user_owner_with_unknown_agent_filter_returns_empty_page() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        persistConv(agentA1, ownerOf(ownerA), base);

        Page<Conversation> page = conversationRepository.listByOwner(
                new ConversationOwner.UserOwner(ownerA),
                Optional.of(new AgentId(UUID.randomUUID())),
                null,
                10);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void list_by_user_owner_filtering_on_other_users_agent_returns_empty_page() {
        // No cross-owner leak even when filtering by an agent owned by someone else.
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        persistConv(agentB1, ownerOf(ownerB), base);

        Page<Conversation> page = conversationRepository.listByOwner(
                new ConversationOwner.UserOwner(ownerA),
                Optional.of(agentB1),
                null,
                10);

        assertThat(page.items()).isEmpty();
    }

    @Test
    void list_by_user_owner_paginates_across_two_pages() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId c1 = persistConv(agentA1, ownerOf(ownerA), base);
        ConversationId c2 = persistConv(agentA1, ownerOf(ownerA), base.plusSeconds(1));
        ConversationId c3 = persistConv(agentA1, ownerOf(ownerA), base.plusSeconds(2));

        Page<Conversation> first = conversationRepository.listByOwner(
                new ConversationOwner.UserOwner(ownerA), Optional.empty(), null, 2);
        assertThat(first.items()).extracting(Conversation::id).containsExactly(c3, c2);
        assertThat(first.nextCursor()).isNotNull();

        Page<Conversation> second = conversationRepository.listByOwner(
                new ConversationOwner.UserOwner(ownerA),
                Optional.empty(),
                first.nextCursor(),
                2);
        assertThat(second.items()).extracting(Conversation::id).containsExactly(c1);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void list_by_system_owner_returns_only_that_clients_rows() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        // USER-owned (must not leak) + SYSTEM-owned.
        persistConv(agentA1, ownerOf(ownerA), base);
        ConversationId sys = persistConv(agentA1, systemOf(clientPrincipal), base.plusSeconds(1));

        Page<Conversation> page = conversationRepository.listByOwner(
                new ConversationOwner.SystemOwner(clientPrincipal),
                Optional.empty(),
                null,
                10);

        assertThat(page.items()).extracting(Conversation::id).containsExactly(sys);
    }

    @Test
    void list_by_owner_with_no_rows_returns_empty_page() {
        Page<Conversation> page = conversationRepository.listByOwner(
                new ConversationOwner.UserOwner(ownerA), Optional.empty(), null, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    // ----- messages -----

    @Test
    void append_message_then_list_messages_returns_chronological_ascending() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId convId = persistConv(agentA1, ownerOf(ownerA), base);

        MessageId mUser = persistMessage(convId, MessageRole.USER, "hi", base.plusSeconds(1));
        MessageId mAssistant = persistMessage(convId, MessageRole.ASSISTANT, "hello", base.plusSeconds(2));

        Page<Message> page = conversationRepository.listMessages(convId, null, 10);
        assertThat(page.items())
                .extracting(Message::id)
                .containsExactly(mUser, mAssistant);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void list_messages_paginates_across_two_pages() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId convId = persistConv(agentA1, ownerOf(ownerA), base);

        MessageId m1 = persistMessage(convId, MessageRole.USER, "1", base.plusSeconds(1));
        MessageId m2 = persistMessage(convId, MessageRole.ASSISTANT, "2", base.plusSeconds(2));
        MessageId m3 = persistMessage(convId, MessageRole.USER, "3", base.plusSeconds(3));

        Page<Message> first = conversationRepository.listMessages(convId, null, 2);
        assertThat(first.items()).extracting(Message::id).containsExactly(m1, m2);
        assertThat(first.nextCursor()).isNotNull();

        Page<Message> second = conversationRepository.listMessages(convId, first.nextCursor(), 2);
        assertThat(second.items()).extracting(Message::id).containsExactly(m3);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void find_last_n_returns_the_last_n_messages_in_chronological_order() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId convId = persistConv(agentA1, ownerOf(ownerA), base);

        persistMessage(convId, MessageRole.USER, "1", base.plusSeconds(1));
        persistMessage(convId, MessageRole.ASSISTANT, "2", base.plusSeconds(2));
        MessageId m3 = persistMessage(convId, MessageRole.USER, "3", base.plusSeconds(3));
        MessageId m4 = persistMessage(convId, MessageRole.ASSISTANT, "4", base.plusSeconds(4));
        MessageId m5 = persistMessage(convId, MessageRole.USER, "5", base.plusSeconds(5));

        List<Message> last3 = conversationRepository.findLastN(convId, 3);
        assertThat(last3).extracting(Message::id).containsExactly(m3, m4, m5);
    }

    // ----- cascade delete -----

    @Test
    void delete_by_id_cascades_through_messages() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId convId = persistConv(agentA1, ownerOf(ownerA), base);
        persistMessage(convId, MessageRole.USER, "hi", base.plusSeconds(1));

        conversationRepository.deleteById(convId);

        assertThat(conversationRepository.findById(convId)).isEmpty();
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = ?",
                Integer.class, convId.value());
        assertThat(remaining).isZero();
    }

    @Test
    void cascade_on_user_delete_removes_conversations_owned_by_that_user() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ConversationId convId = persistConv(agentA1, ownerOf(ownerA), base);

        // Delete the owning user — the FK chain (users → conversations) should
        // cascade through owner_user_id, and conversations → messages cascades
        // by the V001 FK.
        jdbc.update("DELETE FROM agents WHERE owner_id = ?", ownerA.value());
        jdbc.update("DELETE FROM users WHERE id = ?", ownerA.value());

        assertThat(conversationRepository.findById(convId)).isEmpty();
    }

    // ----- helpers -----

    private UserId saveUser(String email) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id, new Email(email), SAMPLE_HASH, Role.STANDARD,
                false, false, now, now));
        return id;
    }

    private AgentId saveAgent(UserId owner, String name) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now));
        return id;
    }

    private ClientId saveApiKey(String clientId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        ClientId cid = new ClientId(clientId);
        apiKeyRepository.save(new ApiKey(cid, SAMPLE_HASH, "ci-probe", false, now));
        return cid;
    }

    private ConversationId persistConv(AgentId agentId, ConversationOwner owner,
                                       OffsetDateTime when) {
        ConversationId id = new ConversationId(UUID.randomUUID());
        conversationRepository.save(new Conversation(
                id, agentId, owner, null, MessageCount.EMPTY, when, when));
        return id;
    }

    private MessageId persistMessage(ConversationId convId, MessageRole role,
                                     String content, OffsetDateTime when) {
        MessageId id = new MessageId(UUID.randomUUID());
        conversationRepository.appendMessage(new Message(
                id, convId, role, new MessageContent(content), when));
        return id;
    }

    private ConversationOwner ownerOf(UserId userId) {
        return new ConversationOwner.UserOwner(userId);
    }

    private ConversationOwner systemOf(ClientId clientId) {
        return new ConversationOwner.SystemOwner(clientId);
    }

    private String stringFor(ConversationId id, String column) {
        return jdbc.queryForObject(
                "SELECT CAST(" + column + " AS TEXT) FROM conversations WHERE id = ?",
                String.class, id.value());
    }
}
