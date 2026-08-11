package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationTest {

    private static final OffsetDateTime CREATED =
            OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UPDATED =
            OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    private static Conversation empty(ConversationId id) {
        return new Conversation(
                id,
                new AgentId(UUID.randomUUID()),
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())),
                null,
                MessageCount.EMPTY,
                CREATED,
                UPDATED);
    }

    private static Conversation empty() {
        return empty(new ConversationId(UUID.randomUUID()));
    }

    // ------- canonical-constructor validation -------

    @Test
    void accepts_all_non_null_fields_and_null_title() {
        Conversation c = empty();
        assertThat(c.title()).isNull();
        assertThat(c.messageCount()).isEqualTo(MessageCount.EMPTY);
    }

    @Test
    void rejects_null_required_references() {
        ConversationId id = new ConversationId(UUID.randomUUID());
        AgentId agentId = new AgentId(UUID.randomUUID());
        ConversationOwner owner =
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID()));

        assertThatNullPointerException().isThrownBy(() -> new Conversation(
                null, agentId, owner, null, MessageCount.EMPTY, CREATED, UPDATED));
        assertThatNullPointerException().isThrownBy(() -> new Conversation(
                id, null, owner, null, MessageCount.EMPTY, CREATED, UPDATED));
        assertThatNullPointerException().isThrownBy(() -> new Conversation(
                id, agentId, null, null, MessageCount.EMPTY, CREATED, UPDATED));
        assertThatNullPointerException().isThrownBy(() -> new Conversation(
                id, agentId, owner, null, null, CREATED, UPDATED));
        assertThatNullPointerException().isThrownBy(() -> new Conversation(
                id, agentId, owner, null, MessageCount.EMPTY, null, UPDATED));
        assertThatNullPointerException().isThrownBy(() -> new Conversation(
                id, agentId, owner, null, MessageCount.EMPTY, CREATED, null));
    }

    // ------- withTitle -------

    @Test
    void with_title_returns_a_copy_with_new_title_and_bumped_updated_at() {
        Conversation original = empty();
        OffsetDateTime now =
                OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        Conversation renamed = original.withTitle(new Title("Renamed"), now);

        assertThat(renamed.id()).isEqualTo(original.id());
        assertThat(renamed.agentId()).isEqualTo(original.agentId());
        assertThat(renamed.owner()).isEqualTo(original.owner());
        assertThat(renamed.messageCount()).isEqualTo(original.messageCount());
        assertThat(renamed.createdAt()).isEqualTo(original.createdAt());
        assertThat(renamed.title()).isEqualTo(new Title("Renamed"));
        assertThat(renamed.updatedAt()).isEqualTo(now);
    }

    @Test
    void with_title_rejects_null_title_with_field_title() {
        Conversation original = empty();
        OffsetDateTime now =
                OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> original.withTitle(null, now))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("title"));
    }

    @Test
    void with_title_rejects_null_now() {
        Conversation original = empty();
        assertThatNullPointerException().isThrownBy(() ->
                original.withTitle(new Title("Renamed"), null));
    }

    // ------- incrementMessageCount -------

    @Test
    void increment_message_count_bumps_count_and_updated_at() {
        Conversation seed = new Conversation(
                new ConversationId(UUID.randomUUID()),
                new AgentId(UUID.randomUUID()),
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())),
                null,
                new MessageCount(63),
                CREATED,
                UPDATED);
        OffsetDateTime now =
                OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        Conversation bumped = seed.incrementMessageCount(now);

        assertThat(bumped.messageCount().value()).isEqualTo(64);
        assertThat(bumped.updatedAt()).isEqualTo(now);
        assertThat(bumped.id()).isEqualTo(seed.id());
    }

    @Test
    void increment_message_count_throws_conversation_full_at_cap() {
        ConversationId id = new ConversationId(
                UUID.fromString("a9b9bb11-1234-4abc-9def-1234567890ab"));
        Conversation full = new Conversation(
                id,
                new AgentId(UUID.randomUUID()),
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())),
                null,
                new MessageCount(64),
                CREATED,
                UPDATED);
        OffsetDateTime now =
                OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        assertThatThrownBy(() -> full.incrementMessageCount(now))
                .isInstanceOf(ConversationFullException.class)
                .hasMessageContaining("a9b9bb11-1234-4abc-9def-1234567890ab");
    }

    @Test
    void increment_message_count_rejects_null_now() {
        Conversation seed = empty();
        assertThatNullPointerException().isThrownBy(() ->
                seed.incrementMessageCount(null));
    }
}
