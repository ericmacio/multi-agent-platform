package com.cognizant.emk.multiagent.infrastructure.agent.validation;

import com.cognizant.emk.multiagent.application.agent.McpReferenceValidator;
import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Catalog-backed implementation of {@link McpReferenceValidator} (US-08-006,
 * REQ-AGT-009). Replaces the EPIC-06 {@code NoopMcpReferenceValidator} stub.
 *
 * <p>For every MCP server name supplied by the agent write path, the validator
 * checks the configured {@link McpServerCatalog}. The first unknown name
 * short-circuits with {@link ValidationException} carrying field
 * {@code "enabledMcpServers"}. An empty input list passes silently — an agent
 * with no MCP servers attached is valid.
 *
 * <p>This adapter wires the {@link McpReferenceValidator} application port
 * (declared by EPIC-06) to the {@link McpServerCatalog} application port
 * (declared by US-08-001) — strictly an infrastructure wiring concern.
 */
@Component
public class CatalogMcpReferenceValidator implements McpReferenceValidator {

    private final McpServerCatalog mcpServerCatalog;

    public CatalogMcpReferenceValidator(McpServerCatalog mcpServerCatalog) {
        this.mcpServerCatalog = mcpServerCatalog;
    }

    @Override
    public void validate(List<String> mcpServerNames) {
        if (mcpServerNames == null || mcpServerNames.isEmpty()) {
            return;
        }
        for (String name : mcpServerNames) {
            if (!mcpServerCatalog.contains(name)) {
                throw new ValidationException("enabledMcpServers", "unknown MCP server: " + name);
            }
        }
    }
}
