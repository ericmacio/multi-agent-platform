package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link DeleteAgentUseCase} implementation.
 */
@Service
public class DeleteAgentService implements DeleteAgentUseCase {

    private final AgentRepository agentRepository;

    public DeleteAgentService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    @Transactional
    public void delete(DeleteAgentCommand command) {
        Agent existing = agentRepository.findById(command.agentId())
                .orElseThrow(() -> new AgentNotFoundException(command.agentId()));
        if (!existing.ownerId().equals(command.ownerId())) {
            throw new AgentNotFoundException(command.agentId());
        }
        agentRepository.delete(command.agentId());
    }
}
