package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the chat memory window for one turn (REQ-AGT-005 / REQ-CHAT-006).
 *
 * <p>Returns the last {@code memorySize} persisted USER / ASSISTANT messages
 * of the conversation in <strong>chronological ascending order</strong>. Pure
 * delegate to {@link ConversationRepository#findLastN(ConversationId, int)};
 * exists as its own class for three reasons:
 *
 * <ol>
 *   <li>The {@link MemorySize} → {@code int} unwrap is centralized — the
 *   domain repository port takes a plain int because the domain cannot
 *   depend on {@code application.shared.PageSize}, and {@code MemorySize}
 *   lives in {@code domain.agent}.</li>
 *   <li>EPIC-12's {@code DelegationService} deliberately bypasses this
 *   helper for sub-agent turns (REQ-AGT-015 — no parent history). Having a
 *   named bean makes the bypass discoverable in code review.</li>
 *   <li>A future "summarize older history into a single synthetic message"
 *   strategy (not in v1) would land as a new method on this class without
 *   touching {@link ChatRequestBuilder}.</li>
 * </ol>
 *
 * <p>Implementation note: when this turn has already persisted the new USER
 * message (which happens inside {@code SendMessageService}'s synchronous
 * prefix BEFORE the assembler is called), the returned window includes that
 * new message as its last element. The LLM thus sees
 * {@code memory window = previous (N-1) + new user}, all in one query.
 * This deviates from US-11-003's original story spec which proposed
 * assembling pre-existing messages and appending the new one separately;
 * the simpler single-query approach is functionally equivalent because
 * {@code findLastN} already orders chronologically ascending.
 */
@Service
public class MemoryWindowAssembler {

    private final ConversationRepository conversationRepository;

    public MemoryWindowAssembler(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    /**
     * Returns the last {@code memorySize.value()} messages of
     * {@code conversationId} in chronological ascending order. Always non-null;
     * empty for a fresh conversation.
     */
    @Transactional(readOnly = true)
    public List<Message> assemble(ConversationId conversationId, MemorySize memorySize) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(memorySize, "memorySize");
        return List.copyOf(conversationRepository.findLastN(
                conversationId, memorySize.value()));
    }
}
