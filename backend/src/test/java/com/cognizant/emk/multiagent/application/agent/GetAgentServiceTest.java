package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.application.agent.GetAgentUseCase.GetAgentQuery;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.user.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAgentServiceTest {

    @Mock private AgentRepository agentRepository;
    @InjectMocks private GetAgentService service;

    @Test
    void returns_the_agent_when_the_caller_is_the_owner() {
        UserId owner = new UserId(UUID.randomUUID());
        AgentId id = new AgentId(UUID.randomUUID());
        Agent agent = agentFor(owner, id);
        when(agentRepository.findById(id)).thenReturn(Optional.of(agent));

        Agent result = service.get(new GetAgentQuery(owner, id));
        assertThat(result).isSameAs(agent);
    }

    @Test
    void unknown_id_raises_AgentNotFoundException() {
        UserId owner = new UserId(UUID.randomUUID());
        AgentId id = new AgentId(UUID.randomUUID());
        when(agentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(new GetAgentQuery(owner, id)))
                .isInstanceOf(AgentNotFoundException.class);
    }

    @Test
    void cross_owner_access_raises_AgentNotFoundException_not_forbidden() {
        UserId callerOwner = new UserId(UUID.randomUUID());
        UserId realOwner = new UserId(UUID.randomUUID());
        AgentId id = new AgentId(UUID.randomUUID());
        Agent agent = agentFor(realOwner, id);
        when(agentRepository.findById(id)).thenReturn(Optional.of(agent));

        assertThatThrownBy(() -> service.get(new GetAgentQuery(callerOwner, id)))
                .isInstanceOf(AgentNotFoundException.class);
    }

    private static Agent agentFor(UserId owner, AgentId id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Agent(
                id, owner, new AgentName("research"),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now);
    }
}
