package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.ListMessagesUseCase.ListMessagesQuery;
import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListMessagesServiceTest {

    @Mock private ConversationRepository conversationRepository;

    @Test
    void owner_match_forwards_to_repository_list_messages() {
        ListMessagesService service = new ListMessagesService(conversationRepository);
        UserId userId = new UserId(UUID.randomUUID());
        ConversationId convId = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(convId))
                .thenReturn(Optional.of(sample(convId, new ConversationOwner.UserOwner(userId))));
        Page<Message> page = new Page<>(List.of(), null, 20);
        when(conversationRepository.listMessages(convId, null, 20)).thenReturn(page);

        Page<Message> result = service.list(new ListMessagesQuery(
                new ConversationOwner.UserOwner(userId), convId, null, new PageSize(20)));

        assertThat(result).isSameAs(page);
        verify(conversationRepository).listMessages(convId, null, 20);
    }

    @Test
    void cross_owner_throws_conversation_not_found_and_never_lists() {
        ListMessagesService service = new ListMessagesService(conversationRepository);
        UserId actualOwner = new UserId(UUID.randomUUID());
        UserId requester = new UserId(UUID.randomUUID());
        ConversationId convId = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(convId))
                .thenReturn(Optional.of(sample(convId, new ConversationOwner.UserOwner(actualOwner))));

        assertThatThrownBy(() -> service.list(new ListMessagesQuery(
                new ConversationOwner.UserOwner(requester), convId, null, new PageSize(20))))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).listMessages(any(), any(Cursor.class), anyInt());
    }

    @Test
    void unknown_conversation_throws_conversation_not_found() {
        ListMessagesService service = new ListMessagesService(conversationRepository);
        ConversationId convId = new ConversationId(UUID.randomUUID());
        when(conversationRepository.findById(convId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(new ListMessagesQuery(
                new ConversationOwner.UserOwner(new UserId(UUID.randomUUID())),
                convId, null, new PageSize(20))))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(conversationRepository, never()).listMessages(any(), any(Cursor.class), anyInt());
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
