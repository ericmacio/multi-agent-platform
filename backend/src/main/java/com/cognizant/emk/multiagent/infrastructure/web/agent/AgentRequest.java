package com.cognizant.emk.multiagent.infrastructure.web.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /agents} and {@code PUT /agents/{id}}.
 *
 * <p>Mirror of the {@code AgentRequest} schema in {@code openapi.yaml}. Every
 * structural / range check is deferred to the domain value objects (the
 * controller constructs {@code AgentName}, {@code MemorySize}, {@code Team},
 * {@code SamplingParams} at the boundary); the bean-validation annotations here
 * are upper-bound length / list-size guards that short-circuit before the
 * controller body runs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRequest(
        @Size(max = 32) String name,
        @Size(max = 1024) String description,
        @Size(max = 1024) String systemPrompt,
        Integer memorySize,
        @Size(max = 64) String llmModel,
        Double temperature,
        Integer maxOutputTokens,
        Double topP,
        @Size(max = 256) List<@Size(max = 64) String> tools,
        @Size(max = 256) List<@Size(max = 64) String> enabledMcpServers,
        @Size(max = 256) List<UUID> team) {
}
