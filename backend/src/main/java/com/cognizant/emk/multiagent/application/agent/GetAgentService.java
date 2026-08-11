package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link GetAgentUseCase} implementation.
 */
@Service
public class GetAgentService implements GetAgentUseCase {

    private final AgentRepository agentRepository;

    public GetAgentService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Agent get(GetAgentQuery query) {
        Agent agent = agentRepository.findById(query.agentId())
                .orElseThrow(() -> new AgentNotFoundException(query.agentId()));
        if (!agent.ownerId().equals(query.ownerId())) {
            // Cross-owner GET surfaces as 404, not 403 (design §8.6): "you can't
            // even know it exists for someone else".
            throw new AgentNotFoundException(query.agentId());
        }
        return agent;
    }
}
