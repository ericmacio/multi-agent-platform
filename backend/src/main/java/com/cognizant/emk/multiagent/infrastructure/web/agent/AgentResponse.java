package com.cognizant.emk.multiagent.infrastructure.web.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response body for every {@code /agents} endpoint. Matches the openapi
 * {@code Agent} schema (AgentRequest fields + server-managed
 * {@code id, ownerId, createdAt, updatedAt}).
 *
 * <p>{@code JsonInclude.NON_NULL}: optional sampling fields are omitted from
 * the wire when null so a "no overrides" agent doesn't carry four explicit
 * null keys.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(
        UUID id,
        UUID ownerId,
        String name,
        String description,
        String systemPrompt,
        Integer memorySize,
        String llmModel,
        Double temperature,
        Integer maxOutputTokens,
        Double topP,
        List<String> tools,
        List<String> enabledMcpServers,
        List<UUID> team,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
