package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.ListConversationsUseCase.ListConversationsQuery;
import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListConversationsServiceTest {

    @Mock private ConversationRepository conversationRepository;

    @Test
    void forwards_query_verbatim_to_repository_and_returns_repository_page() {
        ListConversationsService service = new ListConversationsService(conversationRepository);
        ConversationOwner owner = new ConversationOwner.UserOwner(new UserId(UUID.randomUUID()));
        Optional<AgentId> filter = Optional.of(new AgentId(UUID.randomUUID()));
        Cursor cursor = new Cursor(OffsetDateTime.parse("2026-05-01T10:00:00Z"), UUID.randomUUID().toString());
        PageSize pageSize = new PageSize(20);

        Page<Conversation> page = new Page<>(List.of(), null, 20);
        when(conversationRepository.listByOwner(owner, filter, cursor, 20)).thenReturn(page);

        Page<Conversation> result = service.list(new ListConversationsQuery(owner, filter, cursor, pageSize));

        assertThat(result).isSameAs(page);
        verify(conversationRepository).listByOwner(owner, filter, cursor, 20);
    }

    @Test
    void unwraps_page_size_to_int_before_calling_repository() {
        ListConversationsService service = new ListConversationsService(conversationRepository);
        ConversationOwner owner = new ConversationOwner.UserOwner(new UserId(UUID.randomUUID()));
        Page<Conversation> page = new Page<>(List.of(), null, 100);
        when(conversationRepository.listByOwner(owner, Optional.empty(), null, 100)).thenReturn(page);

        service.list(new ListConversationsQuery(owner, Optional.empty(), null, new PageSize(100)));

        verify(conversationRepository).listByOwner(owner, Optional.empty(), null, 100);
    }
}
