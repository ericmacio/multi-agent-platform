package com.cognizant.emk.multiagent.domain.conversation;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Persisted chat message (REQ-CHAT-009 / REQ-CHAT-012).
 *
 * <p>Only {@link MessageRole#USER} and {@link MessageRole#ASSISTANT} variants
 * exist; tool-call requests/results are transient artifacts of an LLM turn and
 * NEVER materialize as a {@link Message} — the {@link MessageRole} enum has no
 * tool/system variant by design.
 *
 * <p>Messages are append-only: there is no domain helper for updating
 * {@link #content()} or {@link #role()}. The only delete path is cascade on
 * conversation deletion (FK cascade from {@code V001__init_schema.sql}).
 */
public record Message(
        MessageId id,
        ConversationId conversationId,
        MessageRole role,
        MessageContent content,
        OffsetDateTime createdAt) {

    public Message {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
