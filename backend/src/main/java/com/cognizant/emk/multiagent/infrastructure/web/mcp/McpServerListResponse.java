package com.cognizant.emk.multiagent.infrastructure.web.mcp;

import java.util.List;

/**
 * Wire envelope for {@code GET /mcp-servers}. Matches the openapi
 * {@code McpServerList} schema. No pagination — the catalog is small and static
 * (REQ-MCP-001, design §6.2.6).
 */
public record McpServerListResponse(List<McpServerDescriptorResponse> items) {
}
