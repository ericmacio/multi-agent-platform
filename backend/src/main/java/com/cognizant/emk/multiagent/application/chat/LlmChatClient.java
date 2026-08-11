package com.cognizant.emk.multiagent.application.chat;

import reactor.core.publisher.Flux;

/**
 * Provider-agnostic LLM chat-completion port (design §12, REQ-LLM-004 /
 * REQ-ARC-005). The OpenAI adapter is shipped by EPIC-09 / US-09-004 +
 * US-09-005; any future provider would replace it behind this port without
 * touching domain or use-case code.
 *
 * <p>Both methods are documented to NOT declare checked exceptions. Provider
 * failures (HTTP 4xx, 429, 5xx, timeouts, connection refused) surface as the
 * infrastructure exception {@code LlmUnavailableException} — unchecked for the
 * sync path, propagated via {@link Flux#error} for the streaming path. The
 * REST boundary maps that exception to HTTP 502 {@code LLM_UNAVAILABLE}
 * (REQ-LLM-005).
 */
public interface LlmChatClient {

    /**
     * Non-streaming call; the entire assistant response is returned at once.
     */
    ChatResult call(ChatRequest request);

    /**
     * Streaming call; emits one or more {@link ChatChunk} elements then completes.
     * Implementations MUST propagate provider failures via {@code Flux.error(...)}
     * inside the reactive chain, not by throwing synchronously.
     */
    Flux<ChatChunk> stream(ChatRequest request);
}
