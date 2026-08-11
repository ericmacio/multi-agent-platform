package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.CrossOwnerTeamMemberException;
import com.cognizant.emk.multiagent.domain.agent.DuplicateAgentNameException;
import com.cognizant.emk.multiagent.domain.agent.NestedTeamForbiddenException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link UpdateAgentUseCase} implementation.
 */
@Service
public class UpdateAgentService implements UpdateAgentUseCase {

    private final AgentRepository agentRepository;
    private final ToolReferenceValidator toolReferenceValidator;
    private final McpReferenceValidator mcpReferenceValidator;
    private final Clock clock;

    public UpdateAgentService(
            AgentRepository agentRepository,
            ToolReferenceValidator toolReferenceValidator,
            McpReferenceValidator mcpReferenceValidator,
            Clock clock) {
        this.agentRepository = agentRepository;
        this.toolReferenceValidator = toolReferenceValidator;
        this.mcpReferenceValidator = mcpReferenceValidator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Agent replace(UpdateAgentCommand command) {
        Agent existing = agentRepository.findById(command.agentId())
                .orElseThrow(() -> new AgentNotFoundException(command.agentId()));
        if (!existing.ownerId().equals(command.ownerId())) {
            // Same 404-not-403 rule as GET — leaking ownership of another user's
            // agent would be a privacy bug (design §8.6).
            throw new AgentNotFoundException(command.agentId());
        }
        if (agentRepository.existsByOwnerAndNameExcludingId(
                command.ownerId(), command.name(), command.agentId())) {
            throw new DuplicateAgentNameException(command.name());
        }
        toolReferenceValidator.validate(command.tools());
        mcpReferenceValidator.validate(command.enabledMcpServers());
        validateTeamMembers(command.ownerId(), command.agentId(), command.team().members());

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        Agent toSave = existing.withReplacement(
                command.name(),
                command.description(),
                command.systemPrompt(),
                command.memorySize(),
                command.samplingParams(),
                command.tools(),
                command.enabledMcpServers(),
                command.team(),
                now);
        return agentRepository.save(toSave);
    }

    private void validateTeamMembers(UserId ownerId, AgentId selfId, List<AgentId> members) {
        for (AgentId memberId : members) {
            if (memberId.equals(selfId)) {
                // Self-reference would imply a non-empty team on the agent being
                // replaced — REQ-AGT-013 collapses that into NESTED_TEAM_FORBIDDEN
                // (and the DB-level check on agent_team blocks it too at the
                // schema layer).
                throw NestedTeamForbiddenException.selfReference(selfId);
            }
            UserId memberOwner = agentRepository.findOwnerOf(memberId)
                    .orElseThrow(() -> new CrossOwnerTeamMemberException(memberId));
            if (!memberOwner.equals(ownerId)) {
                throw new CrossOwnerTeamMemberException(memberId);
            }
            if (agentRepository.hasNonEmptyTeam(memberId)) {
                throw new NestedTeamForbiddenException(memberId);
            }
        }
    }
}
