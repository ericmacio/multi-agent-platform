package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.BusinessException;

/**
 * Raised at delegation time when the {@code DelegationService} cannot honor a
 * tool-call request from the LLM: the parent agent has vanished, the requested
 * target is not in the parent's current team list, the target agent itself has
 * a non-empty team (single-level rule — REQ-AGT-013), or the target's owner
 * differs from the parent's (REQ-AGT-012). Each of these states is impossible
 * if EPIC-06's write-time validators ran correctly; the runtime check exists
 * to catch a regression in the write path before it manifests as a malformed
 * delegation.
 *
 * <p>Because the state should never be reached in production, this is not a
 * user-input error. The REST adapter surfaces it via the generic {@code
 * Throwable} handler (HTTP 500 / {@code INTERNAL_ERROR}); EPIC-12's
 * end-to-end test (US-12-004) exercises the path where it surfaces inside the
 * Spring AI tool callback and propagates to the SSE error frame as
 * {@code LLM_UNAVAILABLE} (the LLM emitted an invalid tool call → treated as
 * an infrastructure failure of the model).
 *
 * <p>The message is sanitized for log redaction: it carries only the two
 * public UUIDs (parent agent id, offending target id) and never an agent name
 * or description.
 */
public final class InvalidDelegationTargetException extends BusinessException {

    private final AgentId parentAgentId;
    private final AgentId targetMemberId;

    public InvalidDelegationTargetException(AgentId parentAgentId, AgentId targetMemberId, String reason) {
        super("Invalid delegation target: parent="
                + parentAgentId.value() + ", target=" + targetMemberId.value() + " (" + reason + ")");
        this.parentAgentId = parentAgentId;
        this.targetMemberId = targetMemberId;
    }

    public AgentId parentAgentId() {
        return parentAgentId;
    }

    public AgentId targetMemberId() {
        return targetMemberId;
    }
}
