package com.cognizant.emk.multiagent.domain.conversation;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of a {@link Message}. Wraps a {@link UUID} so domain code never
 * has to pattern-match on raw UUIDs and the type system tells {@code MessageId}
 * apart from other UUID-based IDs.
 */
public record MessageId(UUID value) {

    public MessageId {
        Objects.requireNonNull(value, "value");
    }
}
