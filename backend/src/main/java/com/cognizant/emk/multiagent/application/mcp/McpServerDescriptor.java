package com.cognizant.emk.multiagent.application.mcp;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Catalog entry describing one configured MCP server (design §14, REQ-MCP-006).
 *
 * <p>The catalog is static and small (two entries in v1: {@code brave-search} and
 * {@code filesystem}). Length / blank validation on {@code name} enforces the same
 * 64-character cap as the openapi contract and the {@code agent_mcp.mcp_server_name
 * varchar(64)} column. {@code description} is nullable — Spring AI's MCP stdio
 * configuration has no description field, so the adapter (US-08-003) derives it
 * from a small internal lookup keyed on the connection name, or returns
 * {@code null} for unknown names.
 *
 * <p>This record lives in {@code application/mcp} rather than {@code domain/mcp}
 * because the MCP catalog has no behavior worth modeling in the domain — the
 * descriptor doubles as the wire shape returned by the {@link McpServerCatalog}
 * port. (Cf. the symmetrical {@code domain.tool.ToolDescriptor} which exists in
 * the domain only because the tool reference validator pulls it through a domain
 * surface; the MCP path goes directly through the application port.)
 */
public record McpServerDescriptor(String name, String description) {

    private static final int MAX_NAME_LENGTH = 64;

    public McpServerDescriptor {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name", "must not be empty");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ValidationException(
                    "name", "must be at most " + MAX_NAME_LENGTH + " characters");
        }
    }
}
