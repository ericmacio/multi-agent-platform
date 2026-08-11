package com.cognizant.emk.multiagent.infrastructure.web.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;

/**
 * Translates the {@link Agent} domain aggregate to {@link AgentResponse}. Pure
 * static — never reads any sensitive field. The agent aggregate doesn't carry
 * a password hash, but the convention from {@code UserResponseMapper} and
 * {@code ApiKeyResponseMapper} stays the same: the mapper is the single point
 * where the wire shape is shaped, so any future field flows through here.
 */
public final class AgentResponseMapper {

    private AgentResponseMapper() {}

    public static AgentResponse toResponse(Agent agent) {
        return new AgentResponse(
                agent.id().value(),
                agent.ownerId().value(),
                agent.name().value(),
                agent.description(),
                agent.systemPrompt(),
                agent.memorySize().value(),
                agent.samplingParams().llmModel(),
                agent.samplingParams().temperature(),
                agent.samplingParams().maxOutputTokens(),
                agent.samplingParams().topP(),
                agent.tools(),
                agent.enabledMcpServers(),
                agent.team().members().stream().map(AgentId::value).toList(),
                agent.createdAt(),
                agent.updatedAt());
    }
}
