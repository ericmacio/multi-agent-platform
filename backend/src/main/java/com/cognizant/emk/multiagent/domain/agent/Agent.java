package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Agent aggregate (REQ-AGT-001, design §4.1).
 *
 * <p>Carries every owner-configurable attribute plus the system-managed
 * {@code id}, {@code ownerId}, {@code createdAt}, {@code updatedAt}. Structural
 * validation lives in the canonical constructor; non-local invariants
 * (unique-name-per-owner, single-level team, cross-owner check) live in the
 * application layer because they need repository access.
 *
 * <p>{@code tools} and {@code enabledMcpServers} are kept as raw {@link String}
 * lists rather than typed value objects because the catalog of valid names is
 * external (EPIC-07 / EPIC-08) and the domain has no business cataloguing them
 * itself. Length-bounded structural validation (≤ 64 chars per entry, dedup) is
 * still enforced here so the {@code agent_tools.tool_name} /
 * {@code agent_mcp_servers.mcp_server_name} columns never overflow.
 */
public record Agent(
        AgentId id,
        UserId ownerId,
        AgentName name,
        String description,
        String systemPrompt,
        MemorySize memorySize,
        SamplingParams samplingParams,
        List<String> tools,
        List<String> enabledMcpServers,
        Team team,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final int MAX_DESCRIPTION_LENGTH = 1024;
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 1024;
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final int MAX_MCP_SERVER_NAME_LENGTH = 64;

    public Agent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(memorySize, "memorySize");
        Objects.requireNonNull(samplingParams, "samplingParams");
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        description = validateNonBlank(description, "description", MAX_DESCRIPTION_LENGTH);
        systemPrompt = validateNonBlank(systemPrompt, "systemPrompt", MAX_SYSTEM_PROMPT_LENGTH);
        tools = validateNameList(tools, "tools", MAX_TOOL_NAME_LENGTH);
        enabledMcpServers = validateNameList(
                enabledMcpServers, "enabledMcpServers", MAX_MCP_SERVER_NAME_LENGTH);
    }

    /**
     * Returns a copy keeping {@code id}, {@code ownerId}, {@code createdAt}, and
     * replacing every other configuration field. {@code updatedAt} is set to
     * {@code now}. Backs the full-replace semantics of {@code PUT /agents/{id}}
     * (US-06-007 / REQ-AGT-014).
     */
    public Agent withReplacement(
            AgentName newName,
            String newDescription,
            String newSystemPrompt,
            MemorySize newMemorySize,
            SamplingParams newSamplingParams,
            List<String> newTools,
            List<String> newEnabledMcpServers,
            Team newTeam,
            OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        return new Agent(
                id,
                ownerId,
                newName,
                newDescription,
                newSystemPrompt,
                newMemorySize,
                newSamplingParams,
                newTools,
                newEnabledMcpServers,
                newTeam,
                createdAt,
                now);
    }

    // ------- helpers -------

    private static String validateNonBlank(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(field, "must not be empty");
        }
        if (raw.length() > maxLength) {
            throw new ValidationException(field, "must be at most " + maxLength + " characters");
        }
        return raw;
    }

    private static List<String> validateNameList(List<String> raw, String field, int maxLength) {
        if (raw == null) {
            return List.of();
        }
        List<String> deduped = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                throw new ValidationException(field, "must not contain blank entries");
            }
            if (entry.length() > maxLength) {
                throw new ValidationException(
                        field, "entries must be at most " + maxLength + " characters");
            }
            if (!seen.add(entry)) {
                throw new ValidationException(
                        field, "must not contain duplicate entries: " + entry);
            }
            deduped.add(entry);
        }
        return Collections.unmodifiableList(deduped);
    }
}
