package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.shared.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ListConversationsUseCase} implementation. Pure forwarder —
 * the REST adapter has already decoded the opaque wire cursor into a
 * domain {@link com.cognizant.emk.multiagent.domain.shared.Cursor}, so this
 * layer just threads the call through to the repository.
 */
@Service
public class ListConversationsService implements ListConversationsUseCase {

    private final ConversationRepository conversationRepository;

    public ListConversationsService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Conversation> list(ListConversationsQuery query) {
        return conversationRepository.listByOwner(
                query.owner(),
                query.agentFilter(),
                query.cursor(),
                query.pageSize().value());
    }
}
