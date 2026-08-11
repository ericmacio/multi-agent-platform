package com.cognizant.emk.multiagent.application.mcp;

import java.util.List;

/**
 * Read-only port over the configured MCP-server catalog (design §14, REQ-MCP-001 /
 * -006).
 *
 * <p>The catalog is populated once at application startup by
 * {@code McpServerCatalogAdapter} (US-08-003) from the
 * {@code spring.ai.mcp.client.stdio.connections.*} configuration tree, then cached
 * for the lifetime of the JVM. Implementations MUST be thread-safe — {@link #all()}
 * returns an unmodifiable snapshot.
 */
public interface McpServerCatalog {

    /** Returns every catalog entry, sorted by {@code name} for deterministic output. */
    List<McpServerDescriptor> all();

    /**
     * Returns {@code true} when {@code name} matches a catalog entry. Case-sensitive,
     * matching the {@code agent_mcp.mcp_server_name} column collation. Backs the
     * agent write-time reference validator (US-08-006, REQ-AGT-009).
     */
    boolean contains(String name);
}
