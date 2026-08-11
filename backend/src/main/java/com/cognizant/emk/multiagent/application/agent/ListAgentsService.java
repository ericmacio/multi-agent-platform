package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.shared.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ListAgentsUseCase} implementation. Pure forwarder — the REST
 * adapter has already decoded the opaque wire cursor into a domain
 * {@code Cursor}, so this layer just threads the call through to the repository.
 */
@Service
public class ListAgentsService implements ListAgentsUseCase {

    private final AgentRepository agentRepository;

    public ListAgentsService(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Agent> list(ListAgentsQuery query) {
        return agentRepository.listByOwner(
                query.ownerId(), query.cursor(), query.pageSize().value());
    }
}
