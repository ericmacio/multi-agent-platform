package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.application.agent.DeleteAgentUseCase.DeleteAgentCommand;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteAgentServiceTest {

    @Mock private AgentRepository agentRepository;
    @InjectMocks private DeleteAgentService service;

    @Test
    void deletes_when_caller_is_the_owner() {
        UserId owner = new UserId(UUID.randomUUID());
        AgentId id = new AgentId(UUID.randomUUID());
        when(agentRepository.findById(id)).thenReturn(Optional.of(agentFor(owner, id)));

        service.delete(new DeleteAgentCommand(owner, id));

        verify(agentRepository).delete(id);
    }

    @Test
    void unknown_id_raises_AgentNotFoundException_and_does_not_delete() {
        UserId owner = new UserId(UUID.randomUUID());
        AgentId id = new AgentId(UUID.randomUUID());
        when(agentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(new DeleteAgentCommand(owner, id)))
                .isInstanceOf(AgentNotFoundException.class);
        verify(agentRepository, never()).delete(id);
    }

    @Test
    void cross_owner_delete_raises_AgentNotFoundException_not_forbidden() {
        UserId caller = new UserId(UUID.randomUUID());
        UserId realOwner = new UserId(UUID.randomUUID());
        AgentId id = new AgentId(UUID.randomUUID());
        when(agentRepository.findById(id)).thenReturn(Optional.of(agentFor(realOwner, id)));

        assertThatThrownBy(() -> service.delete(new DeleteAgentCommand(caller, id)))
                .isInstanceOf(AgentNotFoundException.class);
        verify(agentRepository, never()).delete(id);
    }

    private static Agent agentFor(UserId owner, AgentId id) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new Agent(
                id, owner, new AgentName("x"),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now);
    }
}
