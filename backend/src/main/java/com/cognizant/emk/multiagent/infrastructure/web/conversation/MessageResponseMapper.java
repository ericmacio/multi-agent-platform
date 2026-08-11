package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.domain.conversation.Message;

/**
 * Translates the {@link Message} domain aggregate to {@link MessageResponse}.
 * Static helper, no Spring stereotype — used directly from
 * {@code ConversationsController}.
 */
public final class MessageResponseMapper {

    private MessageResponseMapper() {}

    public static MessageResponse toResponse(Message message) {
        return new MessageResponse(
                message.id().value(),
                message.role().name(),
                message.content().value(),
                message.createdAt());
    }
}
