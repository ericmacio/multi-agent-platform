package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.mcp.FilesystemMcpUserScope;
import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentNotFoundException;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Translates {@link Agent} + memory window into a {@link ChatRequest} ready
 * for {@link LlmChatClient} (US-11-003).
 *
 * <p>Re-fetches the agent on every call — this is the REQ-AGT-014
 * load-bearing piece. There is no per-conversation snapshot of the agent
 * configuration; editing the agent (system prompt, sampling, tools,
 * enabled MCPs, team) takes effect on the very next turn.
 *
 * <p>Defense-in-depth against agent / catalog drift: tool and MCP names on
 * the agent are validated against the static catalogs (loaded once at
 * startup). Unknown names — which the EPIC-07 / EPIC-08 write-time
 * validators forbid — surface as {@link AgentConfigurationDriftException},
 * mapped to 500 by the global handler.
 *
 * <p>Filesystem MCP per-user scoping (REQ-MCP-005): when the agent has
 * {@code filesystem} enabled and the conversation is owned by a
 * {@link ConversationOwner.UserOwner}, the per-user root is materialized on
 * demand via {@link FilesystemMcpUserScope#resolveUserRoot(UserId)} — a
 * side effect that ensures the directory exists before the LLM call begins
 * streaming. SYSTEM-owned conversations are unreachable in v1
 * (US-10-005 makes that a deterministic 404), so the SYSTEM dispatch
 * defensively throws.
 */
@Service
public class ChatRequestBuilder {

    private final AgentRepository agentRepository;
    private final ToolCatalog toolCatalog;
    private final McpServerCatalog mcpServerCatalog;
    private final FilesystemMcpUserScope filesystemMcpUserScope;
    // Injected as a plain String via @Value rather than as a typed
    // ApplicationProperties surface, so the application layer stays free of
    // any infrastructure-layer import (hexagonal rule, enforced by
    // LayeringArchTest.application_does_not_depend_on_infrastructure).
    private final String defaultLlmModel;

    public ChatRequestBuilder(
            AgentRepository agentRepository,
            ToolCatalog toolCatalog,
            McpServerCatalog mcpServerCatalog,
            FilesystemMcpUserScope filesystemMcpUserScope,
            @Value("${app.llm.openai.default-model}") String defaultLlmModel) {
        this.agentRepository = agentRepository;
        this.toolCatalog = toolCatalog;
        this.mcpServerCatalog = mcpServerCatalog;
        this.filesystemMcpUserScope = filesystemMcpUserScope;
        this.defaultLlmModel = defaultLlmModel;
    }

    /**
     * Builds the per-turn {@link ChatRequest}.
     *
     * <p>The {@code memoryWindow} is the chronologically-ASCENDING list of
     * past messages — including the just-persisted USER message at its tail
     * (see {@link MemoryWindowAssembler}'s Javadoc on the single-query
     * approach).
     *
     * @throws AgentNotFoundException                if the agent was deleted
     *                                                mid-turn.
     * @throws AgentConfigurationDriftException      if the agent references a
     *                                                tool or MCP server no longer
     *                                                in the catalog.
     */
    public ChatRequest build(
            AgentId agentId,
            ConversationOwner owner,
            List<Message> memoryWindow) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(memoryWindow, "memoryWindow");

        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new AgentNotFoundException(agentId));

        String model = resolveModel(agent);
        SamplingParameters sampling = resolveSampling(agent);
        List<ChatMessage> history = toChatMessages(memoryWindow);
        List<ToolDescriptor> tools = resolveTools(agent);
        if (!agent.team().members().isEmpty()) {
            // REQ-AGT-011: parent agents with a non-empty team get the
            // `delegate` tool appended to their ChatRequest.tools. Leaf agents
            // do NOT see this descriptor — the LLM cannot call delegate(...)
            // for them. This is the runtime guarantee on top of EPIC-06's
            // static REQ-AGT-013 single-level rule (US-12-003).
            List<ToolDescriptor> withDelegate = new ArrayList<>(tools.size() + 1);
            withDelegate.addAll(tools);
            withDelegate.add(DelegationService.DESCRIPTOR);
            tools = Collections.unmodifiableList(withDelegate);
        }
        List<String> mcps = resolveMcpServers(agent);

        UserId ownerUserId = resolveOwnerUserId(owner);
        maybeMaterializeFilesystemRoot(mcps, ownerUserId);

        return new ChatRequest(
                model,
                agent.systemPrompt(),
                history,
                tools,
                mcps,
                sampling,
                ownerUserId.value());
    }

    // ----- helpers -----

    private String resolveModel(Agent agent) {
        String override = agent.samplingParams().llmModel();
        return (override == null || override.isBlank()) ? defaultLlmModel : override;
    }

    private static SamplingParameters resolveSampling(Agent agent) {
        return new SamplingParameters(
                agent.samplingParams().temperature(),
                agent.samplingParams().maxOutputTokens(),
                agent.samplingParams().topP());
    }

    private static List<ChatMessage> toChatMessages(List<Message> memoryWindow) {
        List<ChatMessage> out = new ArrayList<>(memoryWindow.size());
        for (Message m : memoryWindow) {
            Role role = switch (m.role()) {
                case USER -> Role.USER;
                case ASSISTANT -> Role.ASSISTANT;
            };
            out.add(new ChatMessage(role, m.content().value()));
        }
        return Collections.unmodifiableList(out);
    }

    private List<ToolDescriptor> resolveTools(Agent agent) {
        List<String> names = agent.tools();
        if (names.isEmpty()) {
            return List.of();
        }
        List<ToolDescriptor> all = toolCatalog.all();
        List<ToolDescriptor> resolved = new ArrayList<>(names.size());
        for (String name : names) {
            ToolDescriptor descriptor = all.stream()
                    .filter(td -> td.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AgentConfigurationDriftException(
                            "agent " + agent.id().value()
                                    + " references unknown tool: " + name));
            resolved.add(descriptor);
        }
        return Collections.unmodifiableList(resolved);
    }

    private List<String> resolveMcpServers(Agent agent) {
        List<String> names = agent.enabledMcpServers();
        if (names.isEmpty()) {
            return List.of();
        }
        for (String name : names) {
            if (!mcpServerCatalog.contains(name)) {
                throw new AgentConfigurationDriftException(
                        "agent " + agent.id().value()
                                + " references unknown MCP server: " + name);
            }
        }
        return List.copyOf(names);
    }

    private static UserId resolveOwnerUserId(ConversationOwner owner) {
        if (owner instanceof ConversationOwner.UserOwner u) {
            return u.userId();
        }
        // SYSTEM-owned conversations are unreachable in v1 — StartConversationService
        // (US-10-005) makes them a deterministic 404. Defensive throw so a future
        // SYSTEM-owned-agents EPIC has to consciously revisit this dispatch.
        throw new IllegalStateException(
                "ChatRequest cannot be built for SYSTEM owner in v1 — "
                        + "SYSTEM-owned conversations are not yet supported");
    }

    private void maybeMaterializeFilesystemRoot(List<String> mcps, UserId ownerUserId) {
        if (mcps.contains("filesystem")) {
            filesystemMcpUserScope.resolveUserRoot(ownerUserId);
        }
    }
}
