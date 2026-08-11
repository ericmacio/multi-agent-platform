package com.cognizant.emk.multiagent.infrastructure.web.mcp;

/**
 * Wire shape for one MCP server entry. Matches the openapi {@code McpServerDescriptor}
 * schema (design §6.2.6). The {@code description} field is nullable.
 */
public record McpServerDescriptorResponse(String name, String description) {
}
