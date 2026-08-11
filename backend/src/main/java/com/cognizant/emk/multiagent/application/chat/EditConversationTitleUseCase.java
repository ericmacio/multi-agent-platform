package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationNotFoundException;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.Title;
import java.util.Objects;

/**
 * Use case for {@code PATCH /conversations/{conversationId}} (US-10-008).
 *
 * <p>User-edit clause of REQ-CHAT-005 — the title may be set or replaced at
 * any time after auto-derivation (or even before, while the conversation is
 * still empty). Cross-owner edit surfaces as 404 (existence hiding).
 */
public interface EditConversationTitleUseCase {

    /**
     * @throws ConversationNotFoundException when the id is unknown OR the
     * conversation belongs to a different principal.
     */
    Conversation edit(EditConversationTitleCommand command);

    record EditConversationTitleCommand(
            ConversationOwner owner,
            ConversationId id,
            Title newTitle) {

        public EditConversationTitleCommand {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(newTitle, "newTitle");
        }
    }
}
