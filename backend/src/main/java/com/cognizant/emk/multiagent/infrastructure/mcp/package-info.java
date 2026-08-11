/**
 * MCP bounded context — infrastructure layer. {@code McpServerCatalogAdapter}
 * implements the {@code application.mcp.McpServerCatalog} port by reading Spring
 * AI's {@code spring.ai.mcp.client.stdio.connections.*} configuration at startup
 * and caching the snapshot. {@code FilesystemMcpUserScopeAdapter} (US-08-004) and
 * {@code McpServerException} (US-08-007) join this package once they land.
 */
package com.cognizant.emk.multiagent.infrastructure.mcp;
