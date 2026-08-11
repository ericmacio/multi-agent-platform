package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.application.agent.CreateAgentUseCase.CreateAgentCommand;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.CrossOwnerTeamMemberException;
import com.cognizant.emk.multiagent.domain.agent.DuplicateAgentNameException;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.NestedTeamForbiddenException;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.user.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateAgentServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private ToolReferenceValidator toolReferenceValidator;
    @Mock private McpReferenceValidator mcpReferenceValidator;

    private Clock clock;
    private CreateAgentService service;

    private UserId ownerId;
    private CreateAgentCommand baseCommand;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-05-12T12:00:00Z"), ZoneOffset.UTC);
        service = new CreateAgentService(
                agentRepository, toolReferenceValidator, mcpReferenceValidator, clock);
        ownerId = new UserId(UUID.randomUUID());
        baseCommand = new CreateAgentCommand(
                ownerId,
                new AgentName("research-bot"),
                "Searches the web.",
                "You are helpful.",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of(),
                List.of(),
                Team.EMPTY);
    }

    @Test
    void happy_path_persists_with_fresh_id_and_now_timestamps() {
        when(agentRepository.existsByOwnerAndName(ownerId, baseCommand.name())).thenReturn(false);
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        Agent result = service.create(baseCommand);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).save(captor.capture());
        Agent persisted = captor.getValue();
        assertThat(persisted.id().value()).isNotNull();
        assertThat(persisted.ownerId()).isEqualTo(ownerId);
        assertThat(persisted.name()).isEqualTo(baseCommand.name());
        assertThat(persisted.createdAt()).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(persisted.updatedAt()).isEqualTo(persisted.createdAt());
        assertThat(result).isSameAs(persisted);
        verify(toolReferenceValidator).validate(baseCommand.tools());
        verify(mcpReferenceValidator).validate(baseCommand.enabledMcpServers());
    }

    @Test
    void duplicate_name_short_circuits_before_any_other_validation() {
        when(agentRepository.existsByOwnerAndName(ownerId, baseCommand.name())).thenReturn(true);

        assertThatThrownBy(() -> service.create(baseCommand))
                .isInstanceOf(DuplicateAgentNameException.class)
                .hasMessageContaining("research-bot");

        verify(toolReferenceValidator, never()).validate(any());
        verify(mcpReferenceValidator, never()).validate(any());
        verify(agentRepository, never()).save(any());
    }

    @Test
    void team_member_owned_by_different_user_raises_CrossOwnerTeamMember() {
        AgentId memberId = new AgentId(UUID.randomUUID());
        UserId otherOwner = new UserId(UUID.randomUUID());
        when(agentRepository.existsByOwnerAndName(ownerId, baseCommand.name())).thenReturn(false);
        when(agentRepository.findOwnerOf(memberId)).thenReturn(Optional.of(otherOwner));

        CreateAgentCommand cmd = withTeam(memberId);
        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(CrossOwnerTeamMemberException.class)
                .hasMessageContaining(memberId.value().toString());

        verify(agentRepository, never()).save(any());
    }

    @Test
    void team_member_that_doesnt_exist_raises_CrossOwnerTeamMember() {
        AgentId memberId = new AgentId(UUID.randomUUID());
        when(agentRepository.existsByOwnerAndName(ownerId, baseCommand.name())).thenReturn(false);
        when(agentRepository.findOwnerOf(memberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(withTeam(memberId)))
                .isInstanceOf(CrossOwnerTeamMemberException.class);
    }

    @Test
    void team_member_with_non_empty_team_raises_NestedTeamForbidden() {
        AgentId memberId = new AgentId(UUID.randomUUID());
        when(agentRepository.existsByOwnerAndName(ownerId, baseCommand.name())).thenReturn(false);
        when(agentRepository.findOwnerOf(memberId)).thenReturn(Optional.of(ownerId));
        when(agentRepository.hasNonEmptyTeam(memberId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(withTeam(memberId)))
                .isInstanceOf(NestedTeamForbiddenException.class)
                .hasMessageContaining(memberId.value().toString());

        verify(agentRepository, never()).save(any());
    }

    private CreateAgentCommand withTeam(AgentId memberId) {
        return new CreateAgentCommand(
                ownerId,
                baseCommand.name(),
                baseCommand.description(),
                baseCommand.systemPrompt(),
                baseCommand.memorySize(),
                baseCommand.samplingParams(),
                baseCommand.tools(),
                baseCommand.enabledMcpServers(),
                new Team(List.of(memberId)));
    }
}
