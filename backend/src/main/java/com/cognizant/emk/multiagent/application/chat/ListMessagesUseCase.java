package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import java.util.Objects;

/**
 * Use case for {@code GET /conversations/{conversationId}/messages}
 * (US-10-010). Returns messages in chronological ascending order — opposite
 * of the conversations list and what the openapi documents.
 *
 * <p>Verifies parent-conversation ownership first; cross-owner / unknown
 * surfaces as 404.
 */
public interface ListMessagesUseCase {

    /**
     * @throws ConversationNotFoundException when the parent conversation is
     * unknown OR belongs to a different principal.
     */
    Page<Message> list(ListMessagesQuery query);

    record ListMessagesQuery(
            ConversationOwner owner,
            ConversationId conversationId,
            Cursor cursor,
            PageSize pageSize) {

        public ListMessagesQuery {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(pageSize, "pageSize");
        }
    }
}
