package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.shared.PageSize;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import java.util.Objects;
import java.util.Optional;

/**
 * Use case for {@code GET /conversations} (US-10-006).
 *
 * <p>Owner-scoped at the repository layer — the service never sees rows
 * belonging to a different principal. Optionally narrowed to a single
 * agent via {@link ListConversationsQuery#agentFilter()}. An unknown or
 * cross-owner agent in the filter yields an empty page (no 404 leak on
 * agent existence).
 */
public interface ListConversationsUseCase {

    Page<Conversation> list(ListConversationsQuery query);

    record ListConversationsQuery(
            ConversationOwner owner,
            Optional<AgentId> agentFilter,
            Cursor cursor,
            PageSize pageSize) {

        public ListConversationsQuery {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(agentFilter, "agentFilter");
            Objects.requireNonNull(pageSize, "pageSize");
        }
    }
}
