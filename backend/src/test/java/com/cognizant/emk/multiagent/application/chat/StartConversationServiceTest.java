package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.StartConversationUseCase.StartConversationCommand;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartConversationServiceTest {

    @Mock private AgentRepository agentRepository;
    @Mock private ConversationRepository conversationRepository;

    private Clock clock;
    private StartConversationService service;

    private UserId ownerId;
    private AgentId agentId;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
        service = new StartConversationService(agentRepository, conversationRepository, clock);
        ownerId = new UserId(UUID.randomUUID());
        agentId = new AgentId(UUID.randomUUID());
    }

    // ------- USER happy path -------

    @Test
    void user_owner_with_owned_agent_persists_a_fresh_empty_conversation() {
        Agent agent = sampleAgent(agentId, ownerId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> inv.getArgument(0));

        Conversation result = service.start(new StartConversationCommand(
                new ConversationOwner.UserOwner(ownerId), agentId));

        // Captured value is the persisted aggregate: fresh id, owner = caller,
        // agent = command, title null, count 0, both timestamps = clock.now().
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        Conversation saved = captor.getValue();

        assertThat(saved.id().value()).isNotNull();
        assertThat(saved.agentId()).isEqualTo(agentId);
        assertThat(saved.owner()).isEqualTo(new ConversationOwner.UserOwner(ownerId));
        assertThat(saved.title()).isNull();
        assertThat(saved.messageCount()).isEqualTo(MessageCount.EMPTY);
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        assertThat(saved.createdAt()).isEqualTo(now);
        assertThat(saved.updatedAt()).isEqualTo(now);
        assertThat(result).isEqualTo(saved);
    }

    // ------- USER cross-owner -------

    @Test
    void user_owner_with_other_users_agent_throws_agent_not_found_and_never_persists() {
        UserId otherUser = new UserId(UUID.randomUUID());
        Agent othersAgent = sampleAgent(agentId, otherUser);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(othersAgent));

        assertThatThrownBy(() -> service.start(new StartConversationCommand(
                new ConversationOwner.UserOwner(ownerId), agentId)))
                .isInstanceOf(AgentNotFoundException.class)
                .hasMessageContaining(agentId.value().toString());

        verify(conversationRepository, never()).save(any());
    }

    // ------- agent not in DB -------

    @Test
    void user_owner_with_unknown_agent_throws_agent_not_found() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(new StartConversationCommand(
                new ConversationOwner.UserOwner(ownerId), agentId)))
                .isInstanceOf(AgentNotFoundException.class);

        verify(conversationRepository, never()).save(any());
    }

    // ------- SYSTEM principal (v1 deterministic 404) -------

    @Test
    void system_owner_with_existing_agent_throws_agent_not_found_in_v1() {
        // Even when the agent exists and is owned by SOMEONE, a SYSTEM caller
        // always 404s — no agent is SYSTEM-owned in v1.
        Agent existing = sampleAgent(agentId, ownerId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.start(new StartConversationCommand(
                new ConversationOwner.SystemOwner(new ClientId("svc-a")), agentId)))
                .isInstanceOf(AgentNotFoundException.class);

        verify(conversationRepository, never()).save(any());
    }

    @Test
    void system_owner_with_unknown_agent_also_throws_agent_not_found() {
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(new StartConversationCommand(
                new ConversationOwner.SystemOwner(new ClientId("svc-a")), agentId)))
                .isInstanceOf(AgentNotFoundException.class);

        verify(conversationRepository, never()).save(any());
    }

    // ------- helpers -------

    private static Agent sampleAgent(AgentId id, UserId owner) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-01T10:00:00Z");
        return new Agent(
                id, owner, new AgentName("research-bot"),
                "Searches the web.", "You are helpful.",
                MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now);
    }
}
