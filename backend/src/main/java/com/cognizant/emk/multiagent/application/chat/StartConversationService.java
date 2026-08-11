package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link StartConversationUseCase} implementation.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Resolve the target agent or 404.</li>
 *   <li>Verify the caller owns it (cross-owner / SYSTEM → 404 via
 *   {@link AgentNotFoundException}; the REST adapter maps that to
 *   {@code NOT_FOUND} via {@code GlobalExceptionHandler}).</li>
 *   <li>Persist a fresh {@link Conversation} with {@code title=null},
 *   {@link MessageCount#EMPTY}, and {@code createdAt = updatedAt =
 *   clock.now()}.</li>
 * </ol>
 *
 * <p>v1 contract for SYSTEM principals: deterministic 404. Agents are
 * owned by users only ({@code agents.owner_id references users(id)}); the
 * conversation owner-column split (US-10-002 / V005) is forward-prepared
 * for a future SYSTEM-owned-agents capability but is not yet exercised on
 * the agent side. Documenting the v1 behavior here means future readers
 * see the choice in the same place where it is enforced.
 */
@Service
public class StartConversationService implements StartConversationUseCase {

    private final AgentRepository agentRepository;
    private final ConversationRepository conversationRepository;
    private final Clock clock;

    public StartConversationService(
            AgentRepository agentRepository,
            ConversationRepository conversationRepository,
            Clock clock) {
        this.agentRepository = agentRepository;
        this.conversationRepository = conversationRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Conversation start(StartConversationCommand command) {
        Agent agent = agentRepository.findById(command.agentId())
                .orElseThrow(() -> new AgentNotFoundException(command.agentId()));

        if (command.owner() instanceof ConversationOwner.UserOwner u) {
            if (!agent.ownerId().equals(u.userId())) {
                // Cross-owner: 404, not 403 (design §8.6 / REQ-AUTH-008).
                throw new AgentNotFoundException(command.agentId());
            }
        } else {
            // SystemOwner: no agent is SYSTEM-owned in v1 — REQ-AUTH-007 says
            // SYSTEM SHALL NOT see end-user resources and there is no parallel
            // SYSTEM-owned agent surface yet. Deterministic 404 keeps the
            // contract honest and forward-compatible with a future EPIC.
            throw new AgentNotFoundException(command.agentId());
        }

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        Conversation toSave = new Conversation(
                new ConversationId(UUID.randomUUID()),
                command.agentId(),
                command.owner(),
                null,
                MessageCount.EMPTY,
                now, now);
        return conversationRepository.save(toSave);
    }
}
