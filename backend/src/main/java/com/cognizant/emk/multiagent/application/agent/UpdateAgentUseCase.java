package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.List;
import java.util.Objects;

/**
 * Use case for {@code PUT /agents/{agentId}} (REQ-AGT-014).
 *
 * <p>Full replace: every field except {@code id}, {@code ownerId},
 * {@code createdAt} is overwritten; {@code updatedAt} bumps to {@code clock.now()}.
 * Team rules — single-level, same-owner, no self-reference — re-run on every
 * call.
 */
public interface UpdateAgentUseCase {

    /**
     * @throws AgentNotFoundException when the id is unknown OR the agent belongs
     * to a different owner (same 404-not-403 rule as {@code GET}).
     */
    Agent replace(UpdateAgentCommand command);

    record UpdateAgentCommand(
            UserId ownerId,
            AgentId agentId,
            AgentName name,
            String description,
            String systemPrompt,
            MemorySize memorySize,
            SamplingParams samplingParams,
            List<String> tools,
            List<String> enabledMcpServers,
            Team team) {

        public UpdateAgentCommand {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(memorySize, "memorySize");
            Objects.requireNonNull(samplingParams, "samplingParams");
            Objects.requireNonNull(team, "team");
        }
    }
}
