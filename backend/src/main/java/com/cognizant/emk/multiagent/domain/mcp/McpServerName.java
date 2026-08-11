package com.cognizant.emk.multiagent.domain.mcp;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Name of an MCP server declared in {@code spring.ai.mcp.client.stdio.connections.*}
 * configuration (REQ-MCP-001 / REQ-MCP-006).
 *
 * <p>Stored as-is — Spring AI MCP connection keys are matched verbatim against the
 * configured map, so the value object preserves case. Length / blank validation is
 * enforced here so the {@code agent_mcp.mcp_server_name varchar(64)} column and the
 * openapi {@code McpServerDescriptor.name maxLength: 64} contract are respected by
 * construction.
 *
 * <p>The {@link ValidationException} field is {@code enabledMcpServers} — the only
 * context this value object is constructed from at the API boundary (see
 * {@code POST /agents} / {@code PUT /agents/{id}} payloads).
 */
public record McpServerName(String value) {

    private static final int MAX_LENGTH = 64;

    public McpServerName {
        if (value == null || value.isBlank()) {
            throw new ValidationException("enabledMcpServers", "must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException(
                    "enabledMcpServers", "must be at most " + MAX_LENGTH + " characters");
        }
    }
}
