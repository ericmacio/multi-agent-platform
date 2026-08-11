package com.cognizant.emk.multiagent.application.chat;

/**
 * Role of a message exchanged with the LLM (design §12). Matches the persisted
 * {@code MessageRole} enum in {@code domain/conversation} and the openapi
 * {@code MessageRole} enum: only {@code USER} and {@code ASSISTANT} are exposed —
 * tool-call requests/results are transient inside Spring AI and never surface
 * here (REQ-CHAT-012).
 */
public enum Role {
    USER,
    ASSISTANT
}
