package com.cognizant.emk.multiagent.domain.agent;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of an {@link Agent}. Wraps a {@link UUID} so domain code never has to
 * pattern-match on raw UUIDs and the type system tells {@code AgentId} apart from
 * other UUID-based IDs (notably {@code UserId}).
 */
public record AgentId(UUID value) {

    public AgentId {
        Objects.requireNonNull(value, "value");
    }
}
