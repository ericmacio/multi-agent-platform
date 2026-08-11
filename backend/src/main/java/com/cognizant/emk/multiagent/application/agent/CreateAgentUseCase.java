package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.List;
import java.util.Objects;

/**
 * Use case for {@code POST /agents} (REQ-AGT-001 .. REQ-AGT-008, REQ-AGT-013).
 *
 * <p>The {@code CreateAgentService} runs the three repository-backed invariants
 * documented at §6.2.7:
 * <ul>
 *   <li>Duplicate name per owner → {@code DuplicateAgentNameException};</li>
 *   <li>Team member belongs to another user →
 *   {@code CrossOwnerTeamMemberException};</li>
 *   <li>Team member has its own non-empty team →
 *   {@code NestedTeamForbiddenException}.</li>
 * </ul>
 */
public interface CreateAgentUseCase {

    Agent create(CreateAgentCommand command);

    record CreateAgentCommand(
            UserId ownerId,
            AgentName name,
            String description,
            String systemPrompt,
            MemorySize memorySize,
            SamplingParams samplingParams,
            List<String> tools,
            List<String> enabledMcpServers,
            Team team) {

        public CreateAgentCommand {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(memorySize, "memorySize");
            Objects.requireNonNull(samplingParams, "samplingParams");
            Objects.requireNonNull(team, "team");
        }
    }
}
