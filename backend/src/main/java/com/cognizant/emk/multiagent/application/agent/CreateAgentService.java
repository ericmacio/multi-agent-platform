package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.CrossOwnerTeamMemberException;
import com.cognizant.emk.multiagent.domain.agent.DuplicateAgentNameException;
import com.cognizant.emk.multiagent.domain.agent.NestedTeamForbiddenException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link CreateAgentUseCase} implementation.
 *
 * <p>Pipeline (US-06-004):
 * <ol>
 *   <li>duplicate-name pre-flight ({@link AgentRepository#existsByOwnerAndName});</li>
 *   <li>reference validation (tools + MCP servers — stubs in EPIC-06; the seams
 *   are filled in by EPIC-07 / EPIC-08);</li>
 *   <li>team validation: per-member ownership check (REQ-AGT-012) and
 *   single-level check (REQ-AGT-013);</li>
 *   <li>persist with a fresh {@link AgentId} and {@code createdAt = updatedAt =
 *   clock.now()}.</li>
 * </ol>
 */
@Service
public class CreateAgentService implements CreateAgentUseCase {

    private final AgentRepository agentRepository;
    private final ToolReferenceValidator toolReferenceValidator;
    private final McpReferenceValidator mcpReferenceValidator;
    private final Clock clock;

    public CreateAgentService(
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
    public Agent create(CreateAgentCommand command) {
        if (agentRepository.existsByOwnerAndName(command.ownerId(), command.name())) {
            throw new DuplicateAgentNameException(command.name());
        }
        toolReferenceValidator.validate(command.tools());
        mcpReferenceValidator.validate(command.enabledMcpServers());
        validateTeamMembers(command.ownerId(), command.team().members());

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        Agent toSave = new Agent(
                new AgentId(UUID.randomUUID()),
                command.ownerId(),
                command.name(),
                command.description(),
                command.systemPrompt(),
                command.memorySize(),
                command.samplingParams(),
                command.tools(),
                command.enabledMcpServers(),
                command.team(),
                now, now);
        return agentRepository.save(toSave);
    }

    private void validateTeamMembers(UserId ownerId, java.util.List<AgentId> members) {
        for (AgentId memberId : members) {
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
