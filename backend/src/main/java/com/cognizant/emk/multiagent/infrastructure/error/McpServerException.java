package com.cognizant.emk.multiagent.infrastructure.error;

/**
 * Infrastructure-side exception thrown when an MCP server (or any code wrapping one
 * — notably {@code FilesystemMcpUserScopeAdapter}) fails at runtime. Mapped to HTTP
 * 502 with {@code code = MCP_SERVER_ERROR} by {@code GlobalExceptionHandler}
 * (design §9.3, §14).
 *
 * <p>Messages MUST NOT contain raw MCP payloads or user-controlled paths
 * (REQ-SEC-004). The handler logs the cause at {@code WARN} for operators but the
 * response body always carries the static detail {@code "The MCP server is
 * currently unavailable."}.
 */
public final class McpServerException extends ExternalServiceException {

    public McpServerException(String message) {
        super(message);
    }

    public McpServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
