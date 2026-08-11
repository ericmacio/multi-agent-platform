package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.mcp.FilesystemMcpUserScope;
import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.InvalidDelegationTargetException;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default {@link DelegationService} implementation (US-12-002).
 *
 * <p>Steps every {@code delegate(...)} invocation runs through, in order:
 * <ol>
 *   <li>Re-fetch the parent agent (REQ-AGT-014 — the team list applies the
 *   live-config rule). Missing parent → {@link InvalidDelegationTargetException}.</li>
 *   <li>Assert {@code command.targetMemberId()} is in {@code parent.team()}.
 *   This is the runtime side of REQ-AGT-013 — defense in depth on top of
 *   EPIC-06's write-time validators.</li>
 *   <li>Resolve the target agent. Missing target, OR cross-owner target
 *   (REQ-AGT-012) → {@link InvalidDelegationTargetException}.</li>
 *   <li>Assert the target's own team is empty — single-level rule
 *   (REQ-AGT-013) — re-checked at runtime.</li>
 *   <li>Build a minimal {@link ChatRequest} for the target: NO parent
 *   conversation history (REQ-AGT-015), target's own system prompt /
 *   sampling / tools / MCP servers, filesystem MCP per-user root materialized
 *   under the <strong>parent's</strong> owner (REQ-MCP-005 scopes per the
 *   calling user, who is identical to the target's owner thanks to
 *   REQ-AGT-012). The target's {@code tools} never include any
 *   {@code delegate} descriptor — the target is a leaf by the single-level
 *   rule.</li>
 *   <li>Invoke {@link LlmChatClient#call(ChatRequest)} synchronously
 *   (REQ-AGT-015 — the user sees only the parent's stream, not the
 *   sub-agent's incremental output).</li>
 *   <li>Return {@link DelegationService.DelegationResult} carrying the
 *   target id + the sub-agent's final text. NO persistence — REQ-AGT-015's
 *   "B's exchanges SHALL NOT be persisted" is enforced structurally: this
 *   class has no {@code ConversationRepository} dependency and an ArchUnit
 *   rule (in {@code LayeringArchTest}) prevents a future refactor from
 *   introducing one.</li>
 * </ol>
 *
 * <p>Catalog / scope dependencies are kept narrow: only what is needed to
 * resolve tools and MCP server names against the same catalogs the parent's
 * {@code ChatRequestBuilder} uses. Tool / MCP drift (an agent referencing a
 * name no longer in the catalog) surfaces as
 * {@link AgentConfigurationDriftException}, matching the parent path's
 * behavior — mapped to 500 by the generic handler.
 */
@Service
public class DelegationServiceImpl implements DelegationService {

    private final AgentRepository agentRepository;
    private final ToolCatalog toolCatalog;
    private final McpServerCatalog mcpServerCatalog;
    private final FilesystemMcpUserScope filesystemMcpUserScope;
    // Injected as Optional<>, mirroring SendMessageService (US-11-004): in
    // test profiles where Spring AI's OpenAI autoconfig is excluded no
    // LlmChatClient bean exists, but the application context must still
    // boot. Calling delegate(...) without a provider throws IllegalStateException
    // (mapped to 500 INTERNAL_ERROR — operator misconfiguration, not an
    // upstream availability issue).
    private final Optional<LlmChatClient> llmChatClient;
    // Injected as @Value rather than via ApplicationProperties to keep the
    // application layer free of infrastructure imports — same pattern as
    // ChatRequestBuilder (caught by LayeringArchTest).
    private final String defaultLlmModel;

    public DelegationServiceImpl(
            AgentRepository agentRepository,
            ToolCatalog toolCatalog,
            McpServerCatalog mcpServerCatalog,
            FilesystemMcpUserScope filesystemMcpUserScope,
            Optional<LlmChatClient> llmChatClient,
            @Value("${app.llm.openai.default-model}") String defaultLlmModel) {
        this.agentRepository = agentRepository;
        this.toolCatalog = toolCatalog;
        this.mcpServerCatalog = mcpServerCatalog;
        this.filesystemMcpUserScope = filesystemMcpUserScope;
        this.llmChatClient = llmChatClient;
        this.defaultLlmModel = defaultLlmModel;
    }

    @Override
    public DelegationResult delegate(DelegationCommand command) {
        Agent parent = agentRepository.findById(command.parentAgentId())
                .orElseThrow(() -> invalid(command, "parent agent not found"));

        if (!parent.team().members().contains(command.targetMemberId())) {
            throw invalid(command, "target is not a member of the parent's team");
        }

        Agent target = agentRepository.findById(command.targetMemberId())
                .orElseThrow(() -> invalid(command, "target agent not found"));

        if (!target.ownerId().equals(command.parentOwner())) {
            throw invalid(command, "target belongs to a different owner");
        }

        if (!target.team().members().isEmpty()) {
            throw invalid(command, "target has a non-empty team (nested delegation forbidden)");
        }

        if (llmChatClient.isEmpty()) {
            throw new IllegalStateException(
                    "LLM provider is not configured for this environment");
        }
        ChatRequest targetRequest = buildTargetRequest(target, command);
        ChatResult result = llmChatClient.get().call(targetRequest);

        return new DelegationResult(target.id(), result.text());
    }

    // ----- helpers -----

    private ChatRequest buildTargetRequest(Agent target, DelegationCommand command) {
        String model = resolveModel(target);
        List<ChatMessage> history = List.of(new ChatMessage(Role.USER, command.task()));
        List<ToolDescriptor> tools = resolveTools(target);
        List<String> mcps = resolveMcpServers(target);
        SamplingParameters sampling = new SamplingParameters(
                target.samplingParams().temperature(),
                target.samplingParams().maxOutputTokens(),
                target.samplingParams().topP());

        UserId effectivePrincipal = command.parentOwner();
        if (mcps.contains("filesystem")) {
            // REQ-MCP-005: per-user root resolves under the calling user (the
            // parent's owner). REQ-AGT-012 guarantees parent.owner == target.owner,
            // so this is the same value either way, but the parent's owner is the
            // semantic source of truth ("the user running this turn").
            filesystemMcpUserScope.resolveUserRoot(effectivePrincipal);
        }

        return new ChatRequest(
                model,
                target.systemPrompt(),
                history,
                tools,
                mcps,
                sampling,
                effectivePrincipal.value());
    }

    private String resolveModel(Agent target) {
        String override = target.samplingParams().llmModel();
        return (override == null || override.isBlank()) ? defaultLlmModel : override;
    }

    private List<ToolDescriptor> resolveTools(Agent target) {
        List<String> names = target.tools();
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
                            "agent " + target.id().value()
                                    + " references unknown tool: " + name));
            resolved.add(descriptor);
        }
        return List.copyOf(resolved);
    }

    private List<String> resolveMcpServers(Agent target) {
        List<String> names = target.enabledMcpServers();
        if (names.isEmpty()) {
            return List.of();
        }
        for (String name : names) {
            if (!mcpServerCatalog.contains(name)) {
                throw new AgentConfigurationDriftException(
                        "agent " + target.id().value()
                                + " references unknown MCP server: " + name);
            }
        }
        return List.copyOf(names);
    }

    private static InvalidDelegationTargetException invalid(DelegationCommand command, String reason) {
        return new InvalidDelegationTargetException(
                command.parentAgentId(), command.targetMemberId(), reason);
    }
}
