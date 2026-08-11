package com.cognizant.emk.multiagent.domain.conversation;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of a {@link Conversation}. Wraps a {@link UUID} so domain code
 * never has to pattern-match on raw UUIDs and the type system tells
 * {@code ConversationId} apart from other UUID-based IDs.
 */
public record ConversationId(UUID value) {

    public ConversationId {
        Objects.requireNonNull(value, "value");
    }
}
