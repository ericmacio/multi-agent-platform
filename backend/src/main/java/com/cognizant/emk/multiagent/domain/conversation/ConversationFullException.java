package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;

/**
 * Raised when a write would push a {@link Conversation}'s
 * {@link MessageCount} past the {@value MessageCount#MAX}-message cap
 * (REQ-CHAT-010). Mapped to HTTP 409 with the specific
 * {@code CONVERSATION_FULL} code via the subclass handler shipped by
 * US-10-004 (otherwise the generic {@link ConflictException} handler would
 * default to {@code CONFLICT}).
 *
 * <p>The message carries the conversation id so operators can correlate;
 * the user-facing {@code detail} text is set by the handler and does not
 * include the id.
 */
public final class ConversationFullException extends ConflictException {

    public ConversationFullException(ConversationId conversationId) {
        super("Conversation " + conversationId.value()
                + " has reached the " + MessageCount.MAX + "-message cap");
    }
}
