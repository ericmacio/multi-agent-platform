package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;

/**
 * Raised when an admin or owner tries to use an agent name that is already in
 * use by another agent owned by the same user (REQ-AGT-002).
 *
 * <p>Mapped to HTTP 409 with the specific {@code DUPLICATE_AGENT_NAME} code by
 * the {@code GlobalExceptionHandler} subclass handler (US-06-003); without that
 * handler the generic {@code ConflictException → CONFLICT} mapping from
 * US-05-003 would take over.
 */
public final class DuplicateAgentNameException extends ConflictException {

    public DuplicateAgentNameException(AgentName name) {
        super("Agent name already used by this owner: " + name.value());
    }
}
