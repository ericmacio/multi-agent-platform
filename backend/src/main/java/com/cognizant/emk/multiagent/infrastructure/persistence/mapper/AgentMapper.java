package com.cognizant.emk.multiagent.infrastructure.persistence.mapper;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentMcpJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentTeamJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentToolJpa;
import java.util.List;

/**
 * Translates between the {@link Agent} domain aggregate and the four JPA
 * entities that back it ({@link AgentJpa} plus the three side-row entities).
 *
 * <p>Pure Java — no Spring stereotypes. The {@code AgentRepositoryAdapter} is
 * the single caller. {@link #toAgentJpa(Agent, com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa)}
 * needs a {@code UserJpa} reference for the parent FK; the adapter obtains one
 * via {@code UserJpaRepository.getReferenceById} so we never trigger an extra
 * SELECT just to satisfy the association.
 */
public final class AgentMapper {

    private AgentMapper() {}

    public static Agent toDomain(
            AgentJpa jpa,
            List<AgentToolJpa> tools,
            List<AgentMcpJpa> mcps,
            List<AgentTeamJpa> team) {
        return new Agent(
                new AgentId(jpa.getId()),
                new UserId(jpa.getOwner().getId()),
                new AgentName(jpa.getName()),
                jpa.getDescription(),
                jpa.getSystemPrompt(),
                new MemorySize(jpa.getMemorySize()),
                new SamplingParams(
                        jpa.getLlmModel(),
                        jpa.getTemperature(),
                        jpa.getMaxOutputTokens(),
                        jpa.getTopP()),
                tools.stream().map(AgentToolJpa::getToolName).toList(),
                mcps.stream().map(AgentMcpJpa::getMcpServerName).toList(),
                new Team(team.stream()
                        .map(t -> new AgentId(t.getMemberAgentId()))
                        .toList()),
                jpa.getCreatedAt(),
                jpa.getUpdatedAt());
    }

    public static AgentJpa toAgentJpa(
            Agent agent,
            com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa ownerRef) {
        return new AgentJpa(
                agent.id().value(),
                ownerRef,
                agent.name().value(),
                agent.description(),
                agent.systemPrompt(),
                agent.memorySize().value(),
                agent.samplingParams().llmModel(),
                agent.samplingParams().temperature(),
                agent.samplingParams().maxOutputTokens(),
                agent.samplingParams().topP(),
                agent.createdAt(),
                agent.updatedAt());
    }

    public static List<AgentToolJpa> toToolRows(Agent agent) {
        return agent.tools().stream()
                .map(toolName -> new AgentToolJpa(agent.id().value(), toolName))
                .toList();
    }

    public static List<AgentMcpJpa> toMcpRows(Agent agent) {
        return agent.enabledMcpServers().stream()
                .map(name -> new AgentMcpJpa(agent.id().value(), name))
                .toList();
    }

    public static List<AgentTeamJpa> toTeamRows(Agent agent) {
        return agent.team().members().stream()
                .map(memberId -> new AgentTeamJpa(agent.id().value(), memberId.value()))
                .toList();
    }
}
