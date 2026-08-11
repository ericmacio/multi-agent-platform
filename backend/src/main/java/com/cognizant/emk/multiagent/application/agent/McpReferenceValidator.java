package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.List;

/**
 * Validates that every entry in {@code mcpServerNames} refers to a configured
 * MCP server (REQ-AGT-009).
 *
 * <p>EPIC-06 ships {@code NoopMcpReferenceValidator} which accepts everything;
 * EPIC-08 replaces it with a config-backed implementation. The contract here is:
 * throw {@link ValidationException} with field {@code "enabledMcpServers"} on
 * the first unknown name.
 */
public interface McpReferenceValidator {

    void validate(List<String> mcpServerNames);
}
