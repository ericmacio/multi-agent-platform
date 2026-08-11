package com.cognizant.emk.multiagent.infrastructure.error;

/**
 * Root of the infrastructure-side "external service failed" hierarchy (design §9.1).
 *
 * <p>Maps to HTTP 502 at the REST boundary. Concrete subclasses identify the failing
 * provider so the {@code GlobalExceptionHandler} can attach a stable machine-readable
 * {@code code} to the {@code ProblemDetails} body (e.g. {@code MCP_SERVER_ERROR} for
 * MCP, {@code LLM_UNAVAILABLE} for the OpenAI adapter shipped by EPIC-09). The class
 * is abstract so callers always reach for a concrete subclass — the per-provider
 * identity is part of the type.
 *
 * <p>Per REQ-SEC-004, messages MUST NOT contain raw provider payloads, user-controlled
 * paths, or other potentially sensitive content; the handler logs them at {@code WARN}
 * but never returns them to the client.
 */
public abstract class ExternalServiceException extends RuntimeException {

    protected ExternalServiceException(String message) {
        super(message);
    }

    protected ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
