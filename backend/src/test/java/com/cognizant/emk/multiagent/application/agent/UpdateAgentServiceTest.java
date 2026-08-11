package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.application.agent.UpdateAgentUseCase.UpdateAgentCommand;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateAgentServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private ToolReferenceValidator toolReferenceValidator;
    @Mock private McpReferenceValidator mcpReferenceValidator;

    private Clock clock;
    private UpdateAgentService service;

    private UserId ownerId;
    private AgentId agentId;
    private Agent existing;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-06-12T12:00:00Z"), ZoneOffset.UTC);
        service = new UpdateAgentService(
                agentRepository, toolReferenceValidator, mcpReferenceValidator, clock);
        ownerId = new UserId(UUID.randomUUID());
        agentId = new AgentId(UUID.randomUUID());
        OffsetDateTime earlier = OffsetDateTime.parse("2026-05-01T08:00:00Z");
        existing = new Agent(
                agentId, ownerId, new AgentName("old-name"),
                "old description", "old prompt",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                earlier, earlier);
    }

    @Test
    void happy_path_replaces_fields_keeps_id_owner_created_and_bumps_updated_at() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByOwnerAndNameExcludingId(
                eq(ownerId), eq(new AgentName("new-name")), eq(agentId))).thenReturn(false);
        when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateAgentCommand cmd = new UpdateAgentCommand(
                ownerId, agentId, new AgentName("new-name"),
                "new description", "new prompt",
                new MemorySize(24), new SamplingParams("gpt-4o", 0.7, 1024, 0.95),
                List.of("AwsS3Tool"), List.of("brave-search"), Team.EMPTY);

        Agent result = service.replace(cmd);

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).save(captor.capture());
        Agent saved = captor.getValue();
        assertThat(saved.id()).isEqualTo(agentId);
        assertThat(saved.ownerId()).isEqualTo(ownerId);
        assertThat(saved.createdAt()).isEqualTo(existing.createdAt());
        assertThat(saved.name()).isEqualTo(new AgentName("new-name"));
        assertThat(saved.updatedAt()).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(result).isSameAs(saved);
    }

    @Test
    void unknown_id_raises_AgentNotFoundException() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replace(replaceCommand(Team.EMPTY)))
                .isInstanceOf(AgentNotFoundException.class);
        verify(agentRepository, never()).save(any());
    }

    @Test
    void cross_owner_replace_raises_AgentNotFoundException_not_forbidden() {
        UserId otherOwner = new UserId(UUID.randomUUID());
        Agent othersAgent = new Agent(
                agentId, otherOwner, new AgentName("old-name"),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                existing.createdAt(), existing.updatedAt());
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(othersAgent));

        assertThatThrownBy(() -> service.replace(replaceCommand(Team.EMPTY)))
                .isInstanceOf(AgentNotFoundException.class);
        verify(agentRepository, never()).save(any());
    }

    @Test
    void rename_to_an_existing_other_name_raises_DuplicateAgentNameException() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByOwnerAndNameExcludingId(
                eq(ownerId), any(AgentName.class), eq(agentId))).thenReturn(true);

        assertThatThrownBy(() -> service.replace(replaceCommand(Team.EMPTY)))
                .isInstanceOf(DuplicateAgentNameException.class);
        verify(agentRepository, never()).save(any());
    }

    @Test
    void team_self_reference_raises_NestedTeamForbidden() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByOwnerAndNameExcludingId(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.replace(replaceCommand(new Team(List.of(agentId)))))
                .isInstanceOf(NestedTeamForbiddenException.class);
    }

    @Test
    void team_member_owned_by_different_user_raises_CrossOwnerTeamMember() {
        AgentId otherMember = new AgentId(UUID.randomUUID());
        UserId otherOwner = new UserId(UUID.randomUUID());
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByOwnerAndNameExcludingId(any(), any(), any())).thenReturn(false);
        when(agentRepository.findOwnerOf(otherMember)).thenReturn(Optional.of(otherOwner));

        assertThatThrownBy(() -> service.replace(replaceCommand(new Team(List.of(otherMember)))))
                .isInstanceOf(CrossOwnerTeamMemberException.class);
    }

    @Test
    void team_member_with_non_empty_team_raises_NestedTeamForbidden() {
        AgentId member = new AgentId(UUID.randomUUID());
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existing));
        when(agentRepository.existsByOwnerAndNameExcludingId(any(), any(), any())).thenReturn(false);
        when(agentRepository.findOwnerOf(member)).thenReturn(Optional.of(ownerId));
        when(agentRepository.hasNonEmptyTeam(member)).thenReturn(true);

        assertThatThrownBy(() -> service.replace(replaceCommand(new Team(List.of(member)))))
                .isInstanceOf(NestedTeamForbiddenException.class);
    }

    private UpdateAgentCommand replaceCommand(Team team) {
        return new UpdateAgentCommand(
                ownerId, agentId, new AgentName("new-name"),
                "new description", "new prompt",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), team);
    }
}
