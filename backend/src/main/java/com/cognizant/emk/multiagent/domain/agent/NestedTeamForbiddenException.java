package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ConflictException;

/**
 * Raised when a write would create a nested team — either:
 * <ul>
 *   <li>a team member that itself has a non-empty team, or</li>
 *   <li>a team member equal to the parent agent's id (self-reference, which
 *   would imply a non-empty team on the parent itself).</li>
 * </ul>
 *
 * <p>Mapped to HTTP 409 with the specific {@code NESTED_TEAM_FORBIDDEN} code
 * via the subclass handler in US-06-003 (REQ-AGT-013).
 */
public final class NestedTeamForbiddenException extends ConflictException {

    public NestedTeamForbiddenException(AgentId offendingMemberId) {
        super("Team is flat (single-level only); offending member has its own team: "
                + offendingMemberId.value());
    }

    public static NestedTeamForbiddenException selfReference(AgentId self) {
        return new NestedTeamForbiddenException(self);
    }
}
