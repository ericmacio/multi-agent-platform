package com.cognizant.emk.multiagent.domain.mcp;

import com.cognizant.emk.multiagent.domain.shared.BusinessException;

/**
 * Thrown when an MCP-server name does not match any entry in the configured catalog
 * (design §14, REQ-MCP-006). Write-time agent validation surfaces this as a
 * {@code VALIDATION_ERROR} (see {@code CatalogMcpReferenceValidator}, US-08-006); a
 * chat-turn-time lookup against the catalog is defense-in-depth — the write-path
 * validator should prevent it from ever reaching the runtime.
 */
public class UnknownMcpServerException extends BusinessException {

    private final String name;

    public UnknownMcpServerException(String name) {
        super("unknown MCP server: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
