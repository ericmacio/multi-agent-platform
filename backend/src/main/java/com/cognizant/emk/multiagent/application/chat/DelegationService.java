package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.domain.user.UserId;
import java.util.Objects;

/**
 * Application-layer port driving the {@code delegate(...)} capability of EPIC-12
 * (REQ-AGT-011, REQ-AGT-013, REQ-AGT-015). The Spring AI tool callback shipped by
 * US-12-003 calls this port whenever the parent agent's LLM emits a delegation
 * tool call; the implementation in US-12-002 owns the actual work.
 *
 * <p>Responsibilities of any implementation:
 * <ol>
 *   <li><b>Resolve and validate the target.</b> Re-fetch the parent agent (its
 *   {@code team} is the runtime authority — REQ-AGT-014's live-config rule
 *   applies to the team list too) and assert the requested {@code targetMemberId}
 *   is in the current team list. Re-fetch the target agent and assert that its
 *   own team is empty (single-level rule — REQ-AGT-013 — re-checked at runtime
 *   as defense in depth on top of the write-time enforcement in EPIC-06).</li>
 *   <li><b>Build a minimal {@code ChatRequest} for the target.</b> Only the
 *   delegated task as the user message — NO parent conversation history — plus
 *   the target's own system prompt, tools, MCP servers, and sampling parameters
 *   (REQ-AGT-015 explicit). The target's {@code ChatRequest.tools} MUST NOT
 *   include any {@code delegate} descriptor — the target is a leaf by the
 *   single-level rule.</li>
 *   <li><b>Invoke {@link LlmChatClient#call(ChatRequest)} synchronously.</b>
 *   Sub-agent turns deliberately do not stream — the end-user only sees the
 *   parent's aggregate answer (REQ-AGT-015). The synchronous primitive shipped
 *   by US-09-004 exists for this caller.</li>
 *   <li><b>Persist nothing.</b> The sub-agent's exchange with the LLM is
 *   transient (REQ-AGT-015): it does not land in the parent conversation, it
 *   does not land in a B-owned conversation, and it does not count against the
 *   parent conversation's 64-message cap (REQ-CHAT-010). The implementation
 *   class is forbidden from depending on any conversation persistence type — an
 *   ArchUnit rule enforces this at build time (US-12-002).</li>
 * </ol>
 *
 * <p>Invariant violations are signaled by the domain exception
 * {@code InvalidDelegationTargetException}. Any state in which the parent or
 * target cannot be resolved, the target is not in the team, or the target's
 * team is non-empty is impossible if EPIC-06's write-time validators ran
 * correctly; the runtime check exists to catch a regression in the write path
 * before it manifests as a malformed delegation.
 */
public interface DelegationService {

    /**
     * Identifier of the Spring AI tool that bridges the LLM tool-call payload to
     * {@link #delegate(DelegationCommand)} (US-12-003). Compile-time constant so
     * the infrastructure-side {@code @Tool} annotation in
     * {@code DelegateTool} and the application-side {@link #DESCRIPTOR} cannot
     * drift apart.
     */
    String TOOL_NAME = "delegate";

    /**
     * Description surfaced to the LLM in the tool-calling schema. Compile-time
     * constant — see {@link #TOOL_NAME}.
     */
    String TOOL_DESCRIPTION =
            "Delegate a sub-task to one of this agent's team members. "
                    + "Returns the team member's final answer as plain text.";

    /**
     * Single source of truth for the {@code delegate} descriptor wired into
     * {@code ChatRequest.tools} by {@code ChatRequestBuilder} when the parent
     * agent has a non-empty team. Leaf agents never see this descriptor — the
     * LLM cannot call {@link #delegate(DelegationCommand)} for them (runtime
     * side of REQ-AGT-013).
     */
    ToolDescriptor DESCRIPTOR = new ToolDescriptor(TOOL_NAME, TOOL_DESCRIPTION);

    /**
     * Runs one delegation. Synchronous by construction (see class Javadoc).
     */
    DelegationResult delegate(DelegationCommand command);

    /**
     * Inputs to a single {@link #delegate} invocation. {@code parentAgentId} and
     * {@code parentOwner} come from the chat-turn context (the principal who is
     * running the parent agent's turn); {@code targetMemberId} and {@code task}
     * come from the LLM tool-call payload — both are subject to validation here
     * because the LLM is an untrusted producer.
     */
    record DelegationCommand(
            AgentId parentAgentId,
            UserId parentOwner,
            AgentId targetMemberId,
            String task) {

        private static final int MAX_TASK_LENGTH = 1024;

        public DelegationCommand {
            Objects.requireNonNull(parentAgentId, "parentAgentId");
            Objects.requireNonNull(parentOwner, "parentOwner");
            Objects.requireNonNull(targetMemberId, "targetMemberId");
            if (task == null || task.isBlank()) {
                throw new ValidationException("task", "must not be empty");
            }
            if (task.length() > MAX_TASK_LENGTH) {
                throw new ValidationException(
                        "task", "must be at most " + MAX_TASK_LENGTH + " characters");
            }
        }
    }

    /**
     * The sub-agent's final assistant text, plus the target id for observability
     * and operator logs (REQ-AGT-015 forbids surfacing the sub-agent's output
     * separately to the end-user — the parent agent re-incorporates the answer
     * into its own stream).
     *
     * <p>{@code text} may be the empty string when the sub-agent emitted no
     * tokens — the LLM contract permits an empty assistant turn — and is
     * preserved as-is.
     */
    record DelegationResult(AgentId targetMemberId, String text) {

        public DelegationResult {
            Objects.requireNonNull(targetMemberId, "targetMemberId");
            Objects.requireNonNull(text, "text");
        }
    }
}
