package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.conversation.*;
import com.cognizant.emk.multiagent.domain.shared.BusinessException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default {@link SendMessageUseCase} implementation (US-11-004).
 *
 * <p>Architecture: a synchronous prefix followed by a reactive tail.
 * <ul>
 *   <li>The synchronous prefix runs inside the {@code @Transactional}
 *   boundary of {@link #send(SendMessageCommand)}: load + verify
 *   conversation, check cap, persist USER message, derive title on first
 *   turn. Failures propagate as exceptions out of {@link #send} — Spring
 *   maps them to the matching {@code application/problem+json} body and
 *   the SSE stream is never opened.</li>
 *   <li>The reactive tail (returned {@link Flux}) emits
 *   {@link TurnEvent.Started}, then maps the LLM's chunk stream to
 *   {@link TurnEvent.Delta}s, and on completion persists the ASSISTANT
 *   message inside a SECOND transaction (via {@link TransactionTemplate}
 *   because the reactive callback runs outside the
 *   {@code @Transactional} method invocation).</li>
 * </ul>
 *
 * <p>The {@link LlmChatClient} is injected as an {@link Optional} rather
 * than required. In test profiles where Spring AI's OpenAI autoconfig is
 * excluded (Spring AI 1.1.0 / Spring 7 binary incompat — see
 * {@code OpenAiChatClientAdapter}), no {@code LlmChatClient} bean exists
 * and {@link #send(SendMessageCommand)} fails fast on first invocation
 * with {@link IllegalStateException} (mapped to 500 INTERNAL_ERROR by the
 * generic handler). This pattern lets the bean always exist — every
 * {@code @SpringBootTest} in the suite still boots green — while still
 * failing fast and coherently when an actual chat turn is attempted
 * without a configured provider. The 500 (rather than 502) is correct:
 * a missing LLM provider in production is an operator misconfiguration,
 * not an upstream availability issue.
 */
@Service
public class SendMessageService implements SendMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendMessageService.class);

    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final MemoryWindowAssembler memoryWindowAssembler;
    private final ChatRequestBuilder chatRequestBuilder;
    private final Optional<LlmChatClient> llmChatClient;
    private final TransactionTemplate transactionTemplate;
    private final ChatTurnContext chatTurnContext;
    private final Clock clock;

    public SendMessageService(
            ConversationRepository conversationRepository,
            AgentRepository agentRepository,
            MemoryWindowAssembler memoryWindowAssembler,
            ChatRequestBuilder chatRequestBuilder,
            Optional<LlmChatClient> llmChatClient,
            TransactionTemplate transactionTemplate,
            ChatTurnContext chatTurnContext,
            Clock clock) {
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.memoryWindowAssembler = memoryWindowAssembler;
        this.chatRequestBuilder = chatRequestBuilder;
        this.llmChatClient = llmChatClient;
        this.transactionTemplate = transactionTemplate;
        this.chatTurnContext = chatTurnContext;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Flux<TurnEvent> send(SendMessageCommand command) {
        // Fail fast if no LLM provider was configured in this environment.
        // 500 (via the generic handler) is the right code: this is an
        // operator misconfiguration, not an upstream availability issue.
        if (llmChatClient.isEmpty()) {
            throw new IllegalStateException(
                    "LLM provider is not configured for this environment");
        }

        // ----- Synchronous prefix (in the @Transactional boundary) -----
        Conversation conversation = conversationRepository.findById(command.conversationId())
                .orElseThrow(() -> new ConversationNotFoundException(command.conversationId()));
        if (!conversation.owner().equals(command.owner())) {
            // Cross-principal: 404, not 403 (REQ-AUTH-008 existence hiding).
            throw new ConversationNotFoundException(command.conversationId());
        }
        if (conversation.messageCount().isFull()) {
            // 64-message cap (REQ-CHAT-010). Mapped to 409 CONVERSATION_FULL
            // by US-10-004's handler — the SSE stream is never opened.
            throw new com.cognizant.emk.multiagent.domain.conversation.ConversationFullException(
                    command.conversationId());
        }

        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        Message userMessage = new Message(
                new MessageId(UUID.randomUUID()),
                command.conversationId(),
                MessageRole.USER,
                command.content(),
                now);
        conversationRepository.appendMessage(userMessage);

        // Derive title only on the very first turn AND only when the conversation
        // did not already carry one (REQ-CHAT-005).
        boolean wasFirstTurn = conversation.messageCount().value() == 0;
        boolean hadNoTitle = conversation.title() == null;
        Title derivedTitle = null;
        Conversation afterUser = conversation.incrementMessageCount(now);
        if (wasFirstTurn && hadNoTitle) {
            derivedTitle = Title.fromFirstUserMessage(command.content())
                    .orElseGet(() -> Title.defaultFor(command.conversationId()));
            afterUser = afterUser.withTitle(derivedTitle, now);
        }
        conversationRepository.save(afterUser);

        // Snapshot the owner so the reactive tail (which may run on a different
        // thread) does not re-read mutable state of the parent service.
        final ConversationId convId = command.conversationId();
        final UUID userMessageId = userMessage.id().value();
        final String firstTurnTitleOrNull = derivedTitle == null ? null : derivedTitle.value();
        final var owner = command.owner();
        final var agentId = afterUser.agentId();

        // ----- Reactive tail (cold; only assembled when subscribed) -----
        return Flux.defer(() -> assembleReactiveTail(
                convId, userMessageId, firstTurnTitleOrNull, owner, agentId));
    }

    private Flux<TurnEvent> assembleReactiveTail(
            ConversationId convId,
            UUID userMessageId,
            String firstTurnTitleOrNull,
            ConversationOwner owner,
            AgentId agentId) {

        // 1. Emit Started FIRST — the contract guarantees it's the first
        //    element of a successful subscription, regardless of whether
        //    later steps succeed (the client sees Started → Error in the
        //    failure case, never zero frames).
        Mono<TurnEvent> startedMono = Mono.just(
                (TurnEvent) new TurnEvent.Started(userMessageId, convId.value()));

        // 2. Build the ChatRequest lazily so build-time errors propagate
        //    through the Reactor chain (i.e. AFTER Started has been emitted).
        //    Also populate the ChatTurnContext here so the Spring AI tool
        //    callback for DelegateTool (US-12-003) can resolve the parent
        //    agent / parent owner during the LLM call that follows.
        //    REQ-AGT-014 — owner is read live; SYSTEM owners can't reach
        //    delegation since ChatRequestBuilder rejects them upstream.
        StringBuilder accumulated = new StringBuilder();
        Mono<ChatRequest> requestMono = Mono.fromCallable(() -> {
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new AgentNotFoundException(agentId));
            var window = memoryWindowAssembler.assemble(convId, agent.memorySize());
            if (owner instanceof ConversationOwner.UserOwner u) {
                chatTurnContext.enter(agentId, u.userId());
            }
            return chatRequestBuilder.build(agentId, owner, window);
        });

        // 3. Stream from the LLM, map each chunk to a Delta, accumulate the
        //    text for the assistant-persist step.
        Flux<TurnEvent> deltasAndCompleted = requestMono.flatMapMany(request ->
                llmChatClient.get().stream(request)
                        .doOnNext(chunk -> accumulated.append(chunk.text()))
                        .<TurnEvent>map(chunk -> new TurnEvent.Delta(chunk.text()))
                        .concatWith(Mono.fromCallable(() ->
                                persistAssistantAndBuildCompletedEvent(
                                        convId, accumulated.toString(), firstTurnTitleOrNull))));

        // 4. Compose, add cancellation logging, and clear the turn context
        //    on every terminal signal (completion / error / cancellation).
        //
        //    The clear() is guarded because doFinally often fires on a
        //    reactor thread (e.g., reactor-http-epoll-N when the terminal
        //    signal comes from the LLM WebClient), which does not carry the
        //    servlet request attributes ChatTurnContext's request-scoped
        //    proxy needs. In that case ScopeNotActiveException would leak
        //    to Reactor's onErrorDropped hook. Skipping the explicit clear
        //    on a non-request thread is safe: the request-scoped instance
        //    is discarded when the servlet request finishes, so no state
        //    survives across turns.
        return startedMono.concatWith(deltasAndCompleted)
                .doOnCancel(() -> log.debug(
                        "chat turn cancelled by downstream: conversationId={}",
                        convId.value()))
                .doFinally(signal -> clearTurnContextQuietly());
    }

    private void clearTurnContextQuietly() {
        try {
            chatTurnContext.clear();
        } catch (org.springframework.beans.factory.support.ScopeNotActiveException ex) {
            // Terminal signal reached us on a thread with no bound request
            // (typically the reactor-netty event loop that emitted the LLM
            // stream's onComplete/onError). Nothing to clear on this thread.
            log.debug("chatTurnContext.clear skipped on {}: request scope inactive",
                    Thread.currentThread().getName());
        }
    }

    /**
     * Persists the assistant message and the count bump in a SECOND
     * transaction (the {@code @Transactional} on {@link #send} has already
     * committed by the time the reactive callback runs). Returns the
     * {@link TurnEvent.Completed} carrying the updated message count.
     */
    private TurnEvent persistAssistantAndBuildCompletedEvent(
            ConversationId convId, String accumulatedText, String firstTurnTitleOrNull) {
        return transactionTemplate.execute(status -> {
            OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
            String content = accumulatedText.isEmpty() ? " " : accumulatedText;
            // MessageContent rejects blank; an empty assistant response (very rare,
            // but possible) becomes a single space to satisfy the invariant. The
            // empty wire-level frame is elided by the SSE writer anyway.
            Message assistant = new Message(
                    new MessageId(UUID.randomUUID()),
                    convId,
                    MessageRole.ASSISTANT,
                    new MessageContent(content),
                    now);
            conversationRepository.appendMessage(assistant);

            Conversation reloaded = conversationRepository.findById(convId)
                    .orElseThrow(() -> reloadFailed(convId));
            Conversation bumped = reloaded.incrementMessageCount(now);
            Conversation saved = conversationRepository.save(bumped);

            return new TurnEvent.Completed(
                    assistant.id().value(),
                    firstTurnTitleOrNull,
                    saved.messageCount().value());
        });
    }

    private static BusinessException reloadFailed(ConversationId id) {
        // Should be unreachable: the conversation was persisted in the sync prefix
        // moments earlier. If it vanishes between then and now, surface as
        // ConversationNotFoundException (404). Logged at WARN in the handler.
        return new ConversationNotFoundException(id);
    }
}
