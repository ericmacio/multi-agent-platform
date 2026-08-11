package com.cognizant.emk.multiagent.infrastructure.error;

/**
 * Infrastructure-side exception thrown when the LLM provider (OpenAI in v1,
 * any future provider behind the {@code LlmChatClient} port) fails at
 * runtime. Mapped to HTTP 502 with {@code code = LLM_UNAVAILABLE} by
 * {@code GlobalExceptionHandler} (design §9.3, §12, REQ-LLM-005).
 *
 * <p>Provider 4xx / 429 / 5xx, timeouts, and connection refused all map to
 * this single type — the platform deliberately surfaces provider 429 as our
 * 502, never as our 429 (our rate limit is the global Bucket4j filter,
 * EPIC-13).
 *
 * <p>Messages MUST NOT contain provider payloads, raw prompt text, or
 * {@code OPENAI_API_KEY} fragments (REQ-SEC-004). The handler logs the
 * wrapped cause at {@code WARN} for operators but the response body always
 * carries the static detail {@code "The language-model provider is
 * currently unavailable."}.
 */
public final class LlmUnavailableException extends ExternalServiceException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
