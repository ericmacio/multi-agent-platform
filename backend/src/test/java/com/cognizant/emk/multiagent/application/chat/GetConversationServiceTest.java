package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.GetConversationUseCase.GetConversationQuery;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.auth.ClientId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetConversationServiceTest {

    @Mock private ConversationRepository conversationRepository;

    @Test
    void owner_match_returns_the_conversation() {
        GetConversationService service = new GetConversationService(conversationRepository);
        UserId userId = new UserId(UUID.randomUUID());
        ConversationId id = new ConversationId(UUID.randomUUID());
        Conversation existing = sample(id, new ConversationOwner.UserOwner(userId));
        when(conversationRepository.findById(id)).thenReturn(Optional.of(existing));

        Conversation result = service.get(new GetConversationQuery(
                new ConversationOwner.UserOwner(userId), id));

        assertThat(result).isSameAs(existing);
    }

    @Test
    void cross_user_owner_throws_conversation_not_found() {
        GetConversationService service = new GetConversationService(conversationRepository);
        UserId actualOwner = new UserId(UUID.randomUUID());
        UserId requester = new UserId(UUID.randomUUID());
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(sample(id, new ConversationOwner.UserOwner(actualOwner))));

        assertThatThrownBy(() -> service.get(new GetConversationQuery(
                new ConversationOwner.UserOwner(requester), id)))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void user_requester_against_system_owned_throws_conversation_not_found() {
        GetConversationService service = new GetConversationService(conversationRepository);
        UserId requester = new UserId(UUID.randomUUID());
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(sample(id,
                        new ConversationOwner.SystemOwner(new ClientId("svc-a")))));

        assertThatThrownBy(() -> service.get(new GetConversationQuery(
                new ConversationOwner.UserOwner(requester), id)))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void unknown_id_throws_conversation_not_found() {
        GetConversationService service = new GetConversationService(conversationRepository);
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(new GetConversationQuery(
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())), id)))
                .isInstanceOf(ConversationNotFoundException.class)
                .hasMessageContaining(id.value().toString());
    }

    // ----- helpers -----

    private static Conversation sample(ConversationId id, ConversationOwner owner) {
        OffsetDateTime now = OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        return new Conversation(
                id,
                new AgentId(UUID.randomUUID()),
                owner,
                null,
                MessageCount.EMPTY,
                now, now);
    }
}
