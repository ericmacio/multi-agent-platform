package com.cognizant.emk.multiagent.application.chat;

/**
 * Raised when an agent's persisted configuration references a tool or MCP
 * server that no longer exists in its catalog at chat-turn time. This is a
 * system-state inconsistency, not a user input error: the catalog-backed
 * write-time validators (US-07-005 / US-08-006) reject unknown names, and
 * the catalogs are loaded once at startup and frozen. The exception
 * therefore only fires on a true drift (catalog mutated at runtime, agent
 * persisted via direct SQL, etc.) and surfaces as HTTP 500
 * {@code INTERNAL_ERROR} via the generic {@code Throwable} handler.
 *
 * <p>The message names the offending reference for operator logs but the
 * REST adapter never echoes it back to the client (the generic handler
 * writes a static sanitized body).
 */
public class AgentConfigurationDriftException extends RuntimeException {

    public AgentConfigurationDriftException(String message) {
        super(message);
    }
}
