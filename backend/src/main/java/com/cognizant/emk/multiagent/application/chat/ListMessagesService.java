package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.shared.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ListMessagesUseCase} implementation. Verifies parent
 * conversation ownership first (404 on miss / mismatch), then forwards to
 * {@link ConversationRepository#listMessages}.
 */
@Service
public class ListMessagesService implements ListMessagesUseCase {

    private final ConversationRepository conversationRepository;

    public ListMessagesService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Message> list(ListMessagesQuery query) {
        Conversation parent = conversationRepository.findById(query.conversationId())
                .orElseThrow(() -> new ConversationNotFoundException(query.conversationId()));
        if (!parent.owner().equals(query.owner())) {
            throw new ConversationNotFoundException(query.conversationId());
        }
        return conversationRepository.listMessages(
                query.conversationId(), query.cursor(), query.pageSize().value());
    }
}
