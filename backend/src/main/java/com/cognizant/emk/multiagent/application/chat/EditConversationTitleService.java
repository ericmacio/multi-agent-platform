package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link EditConversationTitleUseCase} implementation. Owner check
 * mirrors {@code GetConversationService}; the title is applied via
 * {@link Conversation#withTitle(com.cognizant.emk.multiagent.domain.conversation.Title,
 * OffsetDateTime)} which bumps {@code updatedAt}.
 */
@Service
public class EditConversationTitleService implements EditConversationTitleUseCase {

    private final ConversationRepository conversationRepository;
    private final Clock clock;

    public EditConversationTitleService(
            ConversationRepository conversationRepository, Clock clock) {
        this.conversationRepository = conversationRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Conversation edit(EditConversationTitleCommand command) {
        Conversation existing = conversationRepository.findById(command.id())
                .orElseThrow(() -> new ConversationNotFoundException(command.id()));
        if (!existing.owner().equals(command.owner())) {
            throw new ConversationNotFoundException(command.id());
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        Conversation renamed = existing.withTitle(command.newTitle(), now);
        return conversationRepository.save(renamed);
    }
}
