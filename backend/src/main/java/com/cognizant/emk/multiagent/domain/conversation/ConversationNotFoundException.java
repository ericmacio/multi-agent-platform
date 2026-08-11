package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.shared.NotFoundException;

/**
 * Raised when a {@link Conversation} lookup by id returns nothing or when a
 * caller targets a conversation that belongs to a different principal
 * (cross-owner access is mapped to 404, not 403, per design §8.6 to avoid
 * leaking existence — REQ-AUTH-008). Mapped to HTTP 404 {@code NOT_FOUND} by
 * the generic {@code NotFoundException} handler in {@code GlobalExceptionHandler}
 * (US-03-001) — no subclass-specific entry is required.
 *
 * <p>The message carries only the UUID — the public identifier — and never any
 * sensitive attribute of the underlying conversation.
 */
public final class ConversationNotFoundException extends NotFoundException {

    public ConversationNotFoundException(ConversationId conversationId) {
        super("Conversation not found: " + conversationId.value());
    }
}
