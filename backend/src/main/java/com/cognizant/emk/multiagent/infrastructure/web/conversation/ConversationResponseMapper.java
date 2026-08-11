package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;

/**
 * Translates the {@link Conversation} domain aggregate to
 * {@link ConversationResponse}. The owner field on the domain side is
 * intentionally NOT exposed on the wire (see {@link ConversationResponse}
 * Javadoc).
 */
public final class ConversationResponseMapper {

    private ConversationResponseMapper() {}

    public static ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(
                conversation.id().value(),
                conversation.agentId().value(),
                conversation.title() == null ? null : conversation.title().value(),
                conversation.messageCount().value(),
                conversation.createdAt(),
                conversation.updatedAt());
    }
}
