package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import java.util.Objects;

/**
 * Use case for {@code DELETE /conversations/{conversationId}} (US-10-009).
 *
 * <p>Hard-delete; the V001 FK cascade ({@code messages.conversation_id …
 * on delete cascade}) handles the message sweep. Cross-owner delete
 * surfaces as 404 (existence hiding).
 */
public interface DeleteConversationUseCase {

    /**
     * @throws ConversationNotFoundException when the id is unknown OR the
     * conversation belongs to a different principal.
     */
    void delete(DeleteConversationCommand command);

    record DeleteConversationCommand(ConversationOwner owner, ConversationId id) {

        public DeleteConversationCommand {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(id, "id");
        }
    }
}
