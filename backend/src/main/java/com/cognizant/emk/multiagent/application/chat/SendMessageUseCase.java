package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * Use case for {@code POST /conversations/{id}/messages} — the only streaming
 * endpoint of the platform (design §7, §16.2).
 *
 * <p>Returns a <strong>cold</strong> {@link Flux} of {@link TurnEvent}: the
 * underlying LLM call is NOT initiated until a subscriber attaches. The REST
 * adapter (US-11-005) is the sole subscriber in production. Calling
 * {@link #send(SendMessageCommand)} runs a small synchronous prefix —
 * ownership / cap pre-flight and USER message persistence — and ONLY then
 * returns the Flux that emits {@link TurnEvent.Started} as its first element.
 *
 * <p>Synchronous failures (cross-owner, 64-message cap, content drift)
 * propagate as exceptions out of {@link #send(SendMessageCommand)} itself
 * BEFORE the Flux is returned — they reach the REST adapter via Spring's
 * normal exception path and the global handler writes the matching
 * {@code application/problem+json} body (404 / 409 / 400). The SSE stream
 * is never opened in that case.
 *
 * <p>Reactive failures (LLM timeout, MCP error, agent deleted mid-turn after
 * USER persistence) surface as {@link Flux#error} — the REST adapter writes
 * the matching {@code error} SSE frame after the {@code started} frame and
 * closes the emitter.
 */
public interface SendMessageUseCase {

    /**
     * Runs the chat turn.
     *
     * <p>Behavior:
     * <ol>
     *   <li>Synchronous prefix: load + verify conversation, check
     *   {@code messageCount < 64}, persist USER message (transactional),
     *   derive title on first message. Throws synchronously on failure;
     *   the Flux is never returned in that case.</li>
     *   <li>Reactive tail: emit {@link TurnEvent.Started}, build memory
     *   window + {@link ChatRequest}, stream from the LLM emitting one
     *   {@link TurnEvent.Delta} per chunk, then on completion persist the
     *   ASSISTANT message and emit {@link TurnEvent.Completed}.</li>
     * </ol>
     */
    Flux<TurnEvent> send(SendMessageCommand command);

    record SendMessageCommand(
            ConversationOwner owner,
            ConversationId conversationId,
            MessageContent content) {

        public SendMessageCommand {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(content, "content");
        }
    }
}
