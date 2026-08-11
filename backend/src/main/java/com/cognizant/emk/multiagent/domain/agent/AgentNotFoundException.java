package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.NotFoundException;

/**
 * Raised when an {@link Agent} lookup by id returns nothing or when a caller
 * targets an agent that belongs to a different user (cross-owner access is
 * mapped to 404, not 403, per design §8.6 to avoid leaking existence). Mapped
 * to HTTP 404 {@code NOT_FOUND} by the {@code GlobalExceptionHandler}.
 *
 * <p>The message carries only the UUID — the public identifier — and never any
 * sensitive attribute of the underlying agent.
 */
public final class AgentNotFoundException extends NotFoundException {

    public AgentNotFoundException(AgentId agentId) {
        super("Agent not found: " + agentId.value());
    }
}
