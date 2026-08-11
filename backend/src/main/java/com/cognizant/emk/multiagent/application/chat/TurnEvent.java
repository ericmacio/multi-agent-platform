package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed in-process event emitted by {@link SendMessageUseCase#send} for one
 * chat turn (design §7.1).
 *
 * <p>The four variants mirror the openapi SSE frame types
 * ({@code started}, {@code delta}, {@code completed}, {@code error}). Carrying
 * them as a sealed in-process sum (rather than directly as wire JSON) means:
 * <ul>
 *   <li>The use case can be tested with {@link reactor.test.StepVerifier}
 *   without HTTP plumbing.</li>
 *   <li>The REST adapter is the single point that translates events to SSE
 *   frames (US-11-005).</li>
 *   <li>An alternative future transport (WebSocket, gRPC) could plug onto the
 *   same {@code Flux<TurnEvent>}.</li>
 * </ul>
 *
 * <p>By convention:
 * <ul>
 *   <li>The first element of a successful turn is exactly one {@link Started}.</li>
 *   <li>Zero or more {@link Delta} elements follow.</li>
 *   <li>The terminal element on success is exactly one {@link Completed}.</li>
 *   <li>On failure, the use case signals
 *   {@link reactor.core.publisher.Flux#error} rather than emitting
 *   {@link Error} — the REST adapter detects the Reactor error and writes
 *   the {@code error} SSE frame. {@link Error} exists only for direct
 *   wire-translation use cases (e.g. SSE adapters writing a sanitized payload
 *   without going through the global exception handler); the use case itself
 *   does not emit it.</li>
 * </ul>
 */
public sealed interface TurnEvent
        permits TurnEvent.Started, TurnEvent.Delta, TurnEvent.Completed, TurnEvent.Error {

    /**
     * Emitted exactly once, after the USER message has been persisted (US-11-004
     * step 2). Carries the persisted user-message id and the parent conversation
     * id so the client can correlate.
     */
    record Started(UUID userMessageId, UUID conversationId) implements TurnEvent {
        public Started {
            Objects.requireNonNull(userMessageId, "userMessageId");
            Objects.requireNonNull(conversationId, "conversationId");
        }
    }

    /**
     * One incremental fragment of the assistant's reply. {@code text} is non-null
     * but MAY be empty (heartbeat / role-only frames from the provider). The SSE
     * adapter elides empty deltas at the wire boundary (US-11-005); they remain
     * representable here so test harnesses can assert the elision rule.
     */
    record Delta(String text) implements TurnEvent {
        public Delta {
            if (text == null) {
                throw new ValidationException("text", "must not be null");
            }
        }
    }

    /**
     * Emitted exactly once at successful end of turn, after the ASSISTANT
     * message has been persisted. {@code title} is non-null only on the
     * very first turn of a conversation (auto-derived per REQ-CHAT-005)
     * AND only when the conversation did not already carry a title.
     * {@code messageCount} is the updated count after both USER and
     * ASSISTANT messages have been persisted.
     */
    record Completed(UUID assistantMessageId, String title, int messageCount) implements TurnEvent {
        public Completed {
            Objects.requireNonNull(assistantMessageId, "assistantMessageId");
            if (messageCount < 1) {
                throw new ValidationException(
                        "messageCount", "must be >= 1 after a completed turn");
            }
            // title is intentionally nullable — REQ-CHAT-005.
        }
    }

    /**
     * Sanitized error payload for SSE-side translation. The use case does NOT
     * emit this — it signals {@link reactor.core.publisher.Flux#error} and the
     * REST adapter builds an {@link Error} from the exception via the same code
     * path the global exception handler uses for synchronous failures. Present
     * here for tests and for future direct-wire use cases.
     */
    record Error(String code, String message) implements TurnEvent {
        public Error {
            if (code == null || code.isBlank()) {
                throw new ValidationException("code", "must not be empty");
            }
            Objects.requireNonNull(message, "message");
        }
    }
}
