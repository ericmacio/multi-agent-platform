package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Conversation aggregate (REQ-CHAT-002 / REQ-CHAT-009, design §4.1).
 *
 * <p>Carries identity, ownership, target agent, optional auto-derived title,
 * the denormalized {@link MessageCount}, and the system-managed timestamps.
 * Messages live in their own {@link Message} aggregate addressed by
 * {@code (conversationId, messageId)} so the 64-message cap can be enforced
 * atomically (via {@link #incrementMessageCount(OffsetDateTime)}) without
 * loading the full message list.
 *
 * <p>{@code title} MAY be {@code null} — it stays so from creation until the
 * first non-empty user message is recorded, at which point EPIC-11's
 * {@code SendMessageService} sets it via {@link #withTitle(Title, OffsetDateTime)}.
 */
public record Conversation(
        ConversationId id,
        AgentId agentId,
        ConversationOwner owner,
        Title title,
        MessageCount messageCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Conversation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(owner, "owner");
        // title is intentionally nullable — REQ-CHAT-005 (auto-derived only on
        // the first non-empty user message; null before that point).
        Objects.requireNonNull(messageCount, "messageCount");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Returns a copy with the new {@link Title} and {@code updatedAt} bumped to
     * {@code now}. Backs both:
     * <ul>
     *   <li>the user-edit path on {@code PATCH /conversations/{id}}
     *   (REQ-CHAT-005 user-edit clause, US-10-008);</li>
     *   <li>the first-user-message auto-derivation path inside
     *   {@code SendMessageService} (EPIC-11).</li>
     * </ul>
     *
     * <p>Clearing the title back to {@code null} is NOT supported: the openapi
     * {@code UpdateConversationRequest.title} is required, and the
     * auto-derivation path always provides a non-null value.
     */
    public Conversation withTitle(Title newTitle, OffsetDateTime now) {
        if (newTitle == null) {
            // Mirrors the ValidationException shape used by the Title constructor
            // so callers handle "missing title" uniformly.
            throw new com.cognizant.emk.multiagent.domain.shared.ValidationException(
                    "title", "must not be empty");
        }
        Objects.requireNonNull(now, "now");
        return new Conversation(id, agentId, owner, newTitle, messageCount, createdAt, now);
    }

    /**
     * Returns a copy with the message count incremented and {@code updatedAt}
     * bumped to {@code now}. Throws {@link ConversationFullException} when the
     * current count is already at {@value MessageCount#MAX} (REQ-CHAT-010).
     */
    public Conversation incrementMessageCount(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        MessageCount bumped = messageCount.incrementOrThrow(id);
        return new Conversation(id, agentId, owner, title, bumped, createdAt, now);
    }
}
