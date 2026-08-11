package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.Objects;

/**
 * Use case for {@code DELETE /agents/{agentId}} (REQ-AGT-010).
 *
 * <p>Hard-delete; the FK cascade chain
 * ({@code agents → agent_tools / agent_mcp_servers / agent_team / conversations
 * → messages}) handles the data sweep. Cross-owner delete surfaces as 404 (not
 * 403) — same privacy rule as GET / PUT.
 */
public interface DeleteAgentUseCase {

    /**
     * @throws AgentNotFoundException when the id is unknown OR the agent
     * belongs to a different owner.
     */
    void delete(DeleteAgentCommand command);

    record DeleteAgentCommand(UserId ownerId, AgentId agentId) {

        public DeleteAgentCommand {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }
}
