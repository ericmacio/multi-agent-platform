package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.domain.shared.NotFoundException;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import com.cognizant.emk.multiagent.infrastructure.error.McpServerException;
import com.cognizant.emk.multiagent.infrastructure.web.error.ProblemDetails;
import org.springframework.http.HttpStatus;

/**
 * Translates an exception signaled by the chat-turn {@link reactor.core.publisher.Flux}
 * into a {@link ProblemDetails} body for the SSE {@code error} frame
 * (US-11-005 / US-11-006).
 *
 * <p>Mirrors the mappings in {@code GlobalExceptionHandler}: the body shape
 * is byte-identical to what the controller would have returned synchronously
 * for the same exception. Kept as a small dedicated helper because the
 * synchronous handler path uses {@code @ExceptionHandler} dispatch (Spring
 * proxy), which is not reachable from inside a Reactor {@code doOnError}
 * callback.
 *
 * <p>Only the small set of exception types the chat path is known to emit is
 * enumerated. Anything else falls through to a sanitized 500 {@code INTERNAL_ERROR}
 * — symmetric with {@code GlobalExceptionHandler.handleUnexpected(...)}.
 */
final class SseErrorTranslator {

    private SseErrorTranslator() {}

    static ProblemDetails translate(Throwable t, String instance) {
        if (t instanceof LlmUnavailableException) {
            return ProblemDetails.of(
                    "LLM_UNAVAILABLE",
                    "LLM unavailable",
                    HttpStatus.BAD_GATEWAY.value(),
                    "The language-model provider is currently unavailable.",
                    instance);
        }
        if (t instanceof McpServerException) {
            return ProblemDetails.of(
                    "MCP_SERVER_ERROR",
                    "MCP server error",
                    HttpStatus.BAD_GATEWAY.value(),
                    "The MCP server is currently unavailable.",
                    instance);
        }
        if (t instanceof NotFoundException nf) {
            // Reachable when e.g. the agent is deleted mid-turn after the user
            // message has been persisted (US-11-004's ChatRequestBuilder build path).
            return ProblemDetails.of(
                    "NOT_FOUND",
                    "Not found",
                    HttpStatus.NOT_FOUND.value(),
                    nf.getMessage(),
                    instance);
        }
        // Anything else: sanitize like the synchronous Throwable handler does.
        return ProblemDetails.of(
                "INTERNAL_ERROR",
                "Internal error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred.",
                instance);
    }
}
