package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.application.chat.ChatTurnContext;
import com.cognizant.emk.multiagent.application.chat.DelegationService;
import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationCommand;
import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationResult;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Spring AI bridge that exposes {@link DelegationService#delegate} to the
 * LLM as a tool-callable function (US-12-003, EPIC-12 TBD-3 resolution).
 *
 * <p>The {@code @Tool}-annotated method's name and description come from
 * {@link DelegationService#TOOL_NAME} / {@link DelegationService#TOOL_DESCRIPTION}
 * — both are compile-time {@code String} constants, so this annotation and
 * {@link DelegationService#DESCRIPTOR} (used by {@code ChatRequestBuilder}
 * to register the tool in the parent agent's {@code ChatRequest.tools})
 * cannot drift apart.
 *
 * <p>Deliberately NOT annotated with {@link com.cognizant.emk.multiagent.domain.tool.ToolGroup}:
 * the tool catalog (EPIC-07) does not list {@code delegate}. The descriptor
 * is appended to {@code ChatRequest.tools} only when the parent agent's
 * team is non-empty (the load-bearing runtime side of REQ-AGT-013 — a leaf
 * agent simply never sees the descriptor and the LLM cannot call
 * {@code delegate(...)} for it).
 *
 * <p>Context propagation: the {@code parentAgentId} and {@code parentOwner}
 * inputs to {@link DelegationCommand} are NOT inputs from the LLM. They
 * describe the current turn and are resolved through the request-scoped
 * {@link ChatTurnContext} populated by {@code SendMessageService}. The LLM
 * inputs are limited to {@code targetMemberId} (UUID string) and
 * {@code task} (plain text) — both are validated and surfaced as a typed
 * {@link DelegationCommand}.
 *
 * <p>Error mapping: malformed input from the LLM (non-UUID
 * {@code targetMemberId}, blank/over-length {@code task}) raises a
 * {@code RuntimeException} that propagates through Spring AI's tool
 * dispatch into the parent's Reactor chain. The SSE error frame surfaces
 * it as {@code LLM_UNAVAILABLE} 502 — "the model returned a malformed
 * tool call" is an infrastructure failure of the model, not a user-input
 * error. {@link com.cognizant.emk.multiagent.domain.agent.InvalidDelegationTargetException}
 * raised from {@code DelegationService} propagates the same way.
 */
@Component
public class DelegateTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateTool.class);

    private final DelegationService delegationService;
    private final ChatTurnContext chatTurnContext;

    public DelegateTool(DelegationService delegationService, ChatTurnContext chatTurnContext) {
        this.delegationService = delegationService;
        this.chatTurnContext = chatTurnContext;
    }

    @Tool(name = DelegationService.TOOL_NAME, description = DelegationService.TOOL_DESCRIPTION)
    public String delegate(
            @ToolParam(description = "UUID of the team member agent to delegate to.")
            String targetMemberId,
            @ToolParam(description = "The sub-task to ask of the team member.")
            String task) {

        AgentId parentAgentId = chatTurnContext.parentAgentId();
        AgentId target = parseAgentId(targetMemberId);

        log.debug("delegate tool invoked: parent={}, target={}",
                parentAgentId.value(), target.value());

        DelegationResult result = delegationService.delegate(new DelegationCommand(
                parentAgentId,
                chatTurnContext.parentOwner(),
                target,
                task));
        return result.text();
    }

    private static AgentId parseAgentId(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("targetMemberId must not be null");
        }
        try {
            return new AgentId(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            // Re-thrown with a clear message so the wire-level surfacing is
            // operator-debuggable. The model-emitted-invalid-UUID case maps
            // to LLM_UNAVAILABLE 502 at the SSE boundary (see class Javadoc).
            throw new IllegalArgumentException(
                    "targetMemberId is not a valid UUID: " + raw, ex);
        }
    }
}
