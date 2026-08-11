package com.cognizant.emk.multiagent.domain.conversation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MessageTest {

    private static final OffsetDateTime CREATED =
            OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    private static Message sample() {
        return new Message(
                new MessageId(UUID.randomUUID()),
                new ConversationId(UUID.randomUUID()),
                MessageRole.USER,
                new MessageContent("hi"),
                CREATED);
    }

    @Test
    void accepts_all_non_null_fields() {
        Message m = sample();
        assertThat(m.role()).isEqualTo(MessageRole.USER);
        assertThat(m.content().value()).isEqualTo("hi");
        assertThat(m.createdAt()).isEqualTo(CREATED);
    }

    @Test
    void rejects_null_id() {
        assertThatNullPointerException().isThrownBy(() -> new Message(
                null,
                new ConversationId(UUID.randomUUID()),
                MessageRole.USER,
                new MessageContent("hi"),
                CREATED)).withMessage("id");
    }

    @Test
    void rejects_null_conversation_id() {
        assertThatNullPointerException().isThrownBy(() -> new Message(
                new MessageId(UUID.randomUUID()),
                null,
                MessageRole.USER,
                new MessageContent("hi"),
                CREATED)).withMessage("conversationId");
    }

    @Test
    void rejects_null_role() {
        assertThatNullPointerException().isThrownBy(() -> new Message(
                new MessageId(UUID.randomUUID()),
                new ConversationId(UUID.randomUUID()),
                null,
                new MessageContent("hi"),
                CREATED)).withMessage("role");
    }

    @Test
    void rejects_null_content() {
        assertThatNullPointerException().isThrownBy(() -> new Message(
                new MessageId(UUID.randomUUID()),
                new ConversationId(UUID.randomUUID()),
                MessageRole.USER,
                null,
                CREATED)).withMessage("content");
    }

    @Test
    void rejects_null_created_at() {
        assertThatNullPointerException().isThrownBy(() -> new Message(
                new MessageId(UUID.randomUUID()),
                new ConversationId(UUID.randomUUID()),
                MessageRole.USER,
                new MessageContent("hi"),
                null)).withMessage("createdAt");
    }
}
