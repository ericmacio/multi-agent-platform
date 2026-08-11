package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link DeleteConversationUseCase} implementation.
 */
@Service
public class DeleteConversationService implements DeleteConversationUseCase {

    private final ConversationRepository conversationRepository;

    public DeleteConversationService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Override
    @Transactional
    public void delete(DeleteConversationCommand command) {
        Conversation existing = conversationRepository.findById(command.id())
                .orElseThrow(() -> new ConversationNotFoundException(command.id()));
        if (!existing.owner().equals(command.owner())) {
            throw new ConversationNotFoundException(command.id());
        }
        conversationRepository.deleteById(command.id());
    }
}
