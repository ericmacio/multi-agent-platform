package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import java.util.Objects;

/**
 * Use case for {@code GET /conversations/{conversationId}} (US-10-007).
 *
 * <p>Cross-owner GET surfaces as 404 (not 403) — design §8.6 existence
 * hiding (REQ-AUTH-008).
 */
public interface GetConversationUseCase {

    /**
     * @throws ConversationNotFoundException when the id is unknown OR the
     * conversation belongs to a different principal.
     */
    Conversation get(GetConversationQuery query);

    record GetConversationQuery(ConversationOwner owner, ConversationId id) {

        public GetConversationQuery {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(id, "id");
        }
    }
}
