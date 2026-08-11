package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;

/**
 * Raised when a write would put an agent in a parent's team while the member
 * belongs to a different owner (REQ-AGT-012).
 *
 * <p>Mapped to HTTP 409 with the specific {@code CROSS_OWNER_TEAM_MEMBER} code
 * via the subclass handler in US-06-003. The member id is safe to surface — it
 * is the public identifier — but the owner id of that member is not, and is
 * never embedded in this message.
 */
public final class CrossOwnerTeamMemberException extends ConflictException {

    public CrossOwnerTeamMemberException(AgentId offendingMemberId) {
        super("Team member belongs to a different owner: " + offendingMemberId.value());
    }
}
