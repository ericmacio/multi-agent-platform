package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Denormalized message count carried on a {@link Conversation} (REQ-CHAT-010).
 *
 * <p>Bounds: {@code 0 <= value <= }{@value #MAX}. The hard cap mirrors the
 * {@code conversations.message_count integer check (between 0 and 64)} column
 * and the openapi cap. Out-of-range values throw {@link ValidationException}
 * with field {@code "messageCount"}.
 *
 * <p>The "bump" operation is exposed as {@link #incrementOrThrow(ConversationId)}
 * (rather than a plain {@code increment()} that hides the failure mode) because
 * incrementing past the cap is a documented business outcome — REQ-CHAT-010 —
 * and the application service surfaces it as {@code CONVERSATION_FULL} 409 via
 * {@link ConversationFullException}.
 */
public record MessageCount(int value) {

    public static final int MIN = 0;
    public static final int MAX = 64;
    public static final MessageCount EMPTY = new MessageCount(MIN);

    public MessageCount {
        if (value < MIN || value > MAX) {
            throw new ValidationException(
                    "messageCount", "must be between " + MIN + " and " + MAX);
        }
    }

    public boolean isFull() {
        return value == MAX;
    }

    /**
     * Returns a new {@link MessageCount} with {@code value + 1}. When the
     * current count is already at the cap, throws
     * {@link ConversationFullException} identifying the offending conversation
     * so the REST layer can surface the exact id.
     */
    public MessageCount incrementOrThrow(ConversationId conversationId) {
        if (isFull()) {
            throw new ConversationFullException(conversationId);
        }
        return new MessageCount(value + 1);
    }
}
