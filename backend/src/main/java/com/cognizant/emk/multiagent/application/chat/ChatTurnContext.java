package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.user.UserId;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

/**
 * Per-turn context bag carrying the parent agent id and parent owner so
 * the Spring AI tool callback (US-12-003 {@code DelegateTool}) can resolve
 * "which chat turn am I running in" without depending on
 * {@code SendMessageService} or threading a context object through every
 * Spring AI tool layer.
 *
 * <p>Lifecycle: {@code SendMessageService} populates an instance at the
 * start of each turn (immediately before invoking
 * {@code ChatRequestBuilder.build(...)}) and clears it on completion via
 * {@code Flux.doFinally(...)} — covers normal completion, errors, and
 * cancellation.
 *
 * <p>Scope: Spring request scope with a CGLIB target-class proxy so
 * singleton beans ({@code SendMessageService}, {@code DelegateTool}) can
 * inject it. Each HTTP request — and thus each chat turn — gets its own
 * instance; concurrent turns on different requests cannot see each other's
 * context. The synchronous prefix of {@code SendMessageService.send(...)}
 * and the {@code Mono.fromCallable} that invokes {@link #enter} both run
 * on the servlet request thread, where the request scope is reachable.
 *
 * <p><b>Known limitation — reactor-thread accesses:</b> once the LLM
 * stream begins emitting (in the reactive tail of the SSE turn), signals
 * flow on the reactor-netty event loop, which has no bound servlet
 * request. Any access to this bean via the CGLIB proxy from a reactor
 * thread — including cleanup in {@code Flux.doFinally(...)} and, if
 * Spring AI dispatches a tool callback mid-stream, {@code DelegateTool}
 * itself — raises {@link org.springframework.beans.factory.support.ScopeNotActiveException}.
 * {@code SendMessageService.clearTurnContextQuietly()} guards the terminal
 * cleanup. The tool-callback path is a latent risk that will surface once
 * delegation is exercised against a real LLM; the fix is to migrate
 * parent-context propagation to Spring AI's {@code ToolContext} so it
 * flows through the {@code ChatRequest} instead of through a
 * request-scoped bean.
 *
 * <p>Defensive access: {@link #parentAgentId()} and {@link #parentOwner()}
 * throw {@link IllegalStateException} if the context has not been entered
 * — invocation of {@code DelegateTool} outside of a chat turn is
 * impossible by construction (the LLM cannot legally emit a tool call
 * without an in-flight turn), so the thrown exception is operator-debug,
 * not user-facing.
 */
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
public class ChatTurnContext {

    private AgentId parentAgentId;
    private UserId parentOwner;

    /**
     * Populate the context for the in-flight turn. Subsequent
     * {@link #parentAgentId()} / {@link #parentOwner()} calls return these
     * values until {@link #clear()} is invoked.
     */
    public void enter(AgentId parentAgentId, UserId parentOwner) {
        this.parentAgentId = parentAgentId;
        this.parentOwner = parentOwner;
    }

    /**
     * Reset the context. Called from {@code SendMessageService}'s
     * {@code Flux.doFinally(...)} so the same request-scoped bean instance
     * cannot accidentally carry state from an aborted turn into the next
     * one (request scope already guarantees fresh state per HTTP request,
     * but explicit cleanup is the simpler invariant to reason about).
     */
    public void clear() {
        this.parentAgentId = null;
        this.parentOwner = null;
    }

    public AgentId parentAgentId() {
        if (parentAgentId == null) {
            throw new IllegalStateException(
                    "ChatTurnContext.parentAgentId() invoked outside of a chat turn");
        }
        return parentAgentId;
    }

    public UserId parentOwner() {
        if (parentOwner == null) {
            throw new IllegalStateException(
                    "ChatTurnContext.parentOwner() invoked outside of a chat turn");
        }
        return parentOwner;
    }
}
