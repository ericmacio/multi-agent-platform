package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.shared.Cursor;
import com.cognizant.emk.multiagent.domain.shared.Page;
import java.util.List;
import java.util.Optional;

/**
 * Domain repository port for the {@link Conversation} aggregate and its child
 * {@link Message} aggregate (design §4.1).
 *
 * <p>Pure Java — no Spring annotations. The adapter implementing this interface
 * lives under {@code infrastructure/persistence/adapter/} and bridges to Spring
 * Data JPA (US-10-003).
 *
 * <p>{@code int pageSize} is used instead of the application-layer
 * {@code PageSize} wrapper because the hexagonal layering rule forbids the
 * domain from referencing the application layer; the application service
 * unwraps {@code PageSize.value()} before calling the port — matching the
 * existing {@code AgentRepository} / {@code ApiKeyRepository} convention.
 *
 * <p>{@link Message} operations live on this port (rather than a separate
 * {@code MessageRepository}) because a {@link Message} is a child aggregate of
 * {@link Conversation} addressed by {@code (conversationId, messageId)}; same
 * shape as {@code AgentRepository} carrying the team-membership operations.
 */
public interface ConversationRepository {

    // ----- Conversation -----

    Conversation save(Conversation conversation);

    Optional<Conversation> findById(ConversationId id);

    /**
     * Returns one page of conversations owned by {@code owner}, ordered
     * newest-first ({@code (createdAt, id) DESC}), optionally narrowed to a
     * single agent via {@code agentFilter}. The adapter switches exhaustively
     * on the sealed {@link ConversationOwner} to dispatch the right
     * {@code where} clause against the V005 XOR columns
     * ({@code owner_user_id} / {@code owner_client_id}).
     */
    Page<Conversation> listByOwner(
            ConversationOwner owner,
            Optional<AgentId> agentFilter,
            Cursor cursor,
            int pageSize);

    /**
     * Hard-deletes the conversation identified by {@code id}. Messages cascade
     * via the V001 FK chain ({@code messages.conversation_id … on delete
     * cascade}). A non-existent id is a silent no-op at this layer; the use
     * case is responsible for surfacing 404.
     */
    void deleteById(ConversationId id);

    // ----- Message -----

    /**
     * Appends a {@link Message} to its parent conversation. Does NOT bump the
     * parent's {@link MessageCount} — the caller is responsible for performing
     * the read-modify-write of {@link Conversation#incrementMessageCount} and
     * {@link #save(Conversation)} in the same transactional boundary (REQ-STR-002
     * persistence ordering).
     */
    Message appendMessage(Message message);

    /**
     * Returns one page of messages of {@code conversationId} in chronological
     * ascending order ({@code (createdAt, id) ASC}) — opposite of the
     * conversations list. Backs {@code GET
     * /conversations/{conversationId}/messages} (US-10-010).
     */
    Page<Message> listMessages(
            ConversationId conversationId,
            Cursor cursor,
            int pageSize);

    /**
     * Returns the last {@code n} messages of {@code conversationId} in
     * chronological ascending order. Backs EPIC-11's memory-window assembly
     * (REQ-AGT-005). Implementations SHOULD query in descending order with
     * {@code LIMIT n} and reverse before returning, so the database can use
     * the {@code (conversation_id, created_at, id)} index.
     */
    List<Message> findLastN(ConversationId conversationId, int n);
}
