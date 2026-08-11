package com.cognizant.emk.multiagent.application.mcp;

import java.util.List;

/**
 * Use case for {@code GET /mcp-servers} (REQ-MCP-006).
 *
 * <p>No command record — the surface is parameterless. The catalog is static, so the
 * use case is a pure forwarder over the {@link McpServerCatalog} port.
 */
public interface ListMcpServersUseCase {

    List<McpServerDescriptor> list();
}
