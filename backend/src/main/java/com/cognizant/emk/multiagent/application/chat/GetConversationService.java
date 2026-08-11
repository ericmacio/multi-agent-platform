package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link GetConversationUseCase} implementation.
 */
@Service
public class GetConversationService implements GetConversationUseCase {

    private final ConversationRepository conversationRepository;

    public GetConversationService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Conversation get(GetConversationQuery query) {
        Conversation conversation = conversationRepository.findById(query.id())
                .orElseThrow(() -> new ConversationNotFoundException(query.id()));
        if (!conversation.owner().equals(query.owner())) {
            // Cross-principal GET surfaces as 404, not 403 (design §8.6 /
            // REQ-AUTH-008): "you can't even know it exists for someone else".
            throw new ConversationNotFoundException(query.id());
        }
        return conversation;
    }
}
