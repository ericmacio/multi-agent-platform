package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import java.util.Objects;

/**
 * Use case for {@code POST /conversations} (US-10-005).
 *
 * <p>Starts a brand-new, empty conversation between the caller and one of
 * their own agents. The owner check is REQ-AGT-006 / REQ-CHAT-001:
 * <ul>
 *   <li>a {@link ConversationOwner.UserOwner} may only chat with an agent
 *   they own (cross-owner read surfaces as 404, not 403 — design §8.6
 *   existence-hiding);</li>
 *   <li>a {@link ConversationOwner.SystemOwner} always 404s in v1 because
 *   no agent is SYSTEM-owned; the {@code agents.owner_id} schema column
 *   still references {@code users(id)} only. A future EPIC may relax this
 *   if a SYSTEM-owned-agents capability lands.</li>
 * </ul>
 *
 * <p>The fresh conversation has {@code title=null} and
 * {@code messageCount=0}; the first non-empty user message
 * (EPIC-11's {@code SendMessageService}) auto-derives the title.
 */
public interface StartConversationUseCase {

    Conversation start(StartConversationCommand command);

    record StartConversationCommand(ConversationOwner owner, AgentId agentId) {

        public StartConversationCommand {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(agentId, "agentId");
        }
    }
}
