package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.DeleteConversationUseCase.DeleteConversationCommand;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteConversationServiceTest {

    @Mock private ConversationRepository conversationRepository;

    @Test
    void owner_match_invokes_delete_by_id_exactly_once() {
        DeleteConversationService service = new DeleteConversationService(conversationRepository);
        UserId userId = new UserId(UUID.randomUUID());
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id)).thenReturn(
                Optional.of(sample(id, new ConversationOwner.UserOwner(userId))));

        service.delete(new DeleteConversationCommand(
                new ConversationOwner.UserOwner(userId), id));

        verify(conversationRepository).deleteById(id);
    }

    @Test
    void cross_owner_throws_conversation_not_found_and_never_deletes() {
        DeleteConversationService service = new DeleteConversationService(conversationRepository);
        UserId actualOwner = new UserId(UUID.randomUUID());
        UserId requester = new UserId(UUID.randomUUID());
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id))
                .thenReturn(Optional.of(sample(id, new ConversationOwner.UserOwner(actualOwner))));

        assertThatThrownBy(() -> service.delete(new DeleteConversationCommand(
                new ConversationOwner.UserOwner(requester), id)))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).deleteById(any());
    }

    @Test
    void unknown_id_throws_conversation_not_found_and_never_deletes() {
        DeleteConversationService service = new DeleteConversationService(conversationRepository);
        ConversationId id = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(new DeleteConversationCommand(
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())), id)))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).deleteById(any());
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
