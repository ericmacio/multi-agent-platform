package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response body for every {@code /conversations} endpoint. Matches the
 * openapi {@code Conversation} schema:
 * {@code id, agentId, title (nullable), messageCount, createdAt, updatedAt}.
 *
 * <p>The owner is intentionally NOT exposed — the caller already knows who
 * they are, and the USER / SYSTEM distinction is internal.
 *
 * <p>{@code JsonInclude.NON_NULL} keeps the {@code title} key out of the
 * wire while the conversation is still empty (REQ-CHAT-005 — auto-derived
 * only on the first non-empty user message).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationResponse(
        UUID id,
        UUID agentId,
        String title,
        int messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
