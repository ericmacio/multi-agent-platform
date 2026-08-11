package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.Objects;

/**
 * Use case for {@code GET /agents/{agentId}} (REQ-AGT-006 / REQ-AUTH-008).
 *
 * <p>Cross-owner access surfaces as {@link AgentNotFoundException} (→ 404), not
 * 403 — leaking that the id exists for another user would be a privacy bug
 * (design §8.6).
 */
public interface GetAgentUseCase {

    /**
     * @throws AgentNotFoundException when no agent matches OR the agent exists
     * but belongs to a different owner. Both cases produce a byte-identical
     * 404 response.
     */
    Agent get(GetAgentQuery query);

    record GetAgentQuery(UserId ownerId, AgentId agentId) {

        public GetAgentQuery {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }
}
