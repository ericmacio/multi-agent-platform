package com.cognizant.emk.multiagent.domain.conversation;

/**
 * Persisted message role (REQ-CHAT-012).
 *
 * <p>Locked to exactly {@link #USER} and {@link #ASSISTANT}. Tool-call requests
 * and tool-call results are transient artifacts of a single LLM turn and are
 * NEVER persisted as messages of the conversation — they have no enum variant
 * here by design.
 *
 * <p>Declaration order ({@code USER}, {@code ASSISTANT}) matches:
 * <ul>
 *   <li>the openapi {@code MessageRole} enum declaration order;</li>
 *   <li>the PostgreSQL check constraint
 *   {@code check (role in ('USER', 'ASSISTANT'))} from
 *   {@code V001__init_schema.sql}.</li>
 * </ul>
 */
public enum MessageRole {
    USER,
    ASSISTANT
}
