package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import java.util.List;
import java.util.UUID;

/**
 * Provider-agnostic LLM chat-completion request (design §12). Snapshot of the
 * agent configuration applicable to a single turn (REQ-AGT-014).
 *
 * <p>The {@code ownerUserId} is carried so the adapter (or EPIC-11's chat-turn
 * wiring) can resolve the per-user filesystem MCP root via
 * {@code FilesystemMcpUserScope} (REQ-MCP-005, TBD-2).
 */
public record ChatRequest(
        String model,
        String systemPrompt,
        List<ChatMessage> history,
        List<ToolDescriptor> tools,
        List<String> enabledMcpServers,
        SamplingParameters sampling,
        UUID ownerUserId) {

    private static final int MAX_MODEL_LENGTH = 64;
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 1024;

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new ValidationException("model", "must not be empty");
        }
        if (model.length() > MAX_MODEL_LENGTH) {
            throw new ValidationException(
                    "model", "must be at most " + MAX_MODEL_LENGTH + " characters");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new ValidationException("systemPrompt", "must not be empty");
        }
        if (systemPrompt.length() > MAX_SYSTEM_PROMPT_LENGTH) {
            throw new ValidationException(
                    "systemPrompt", "must be at most " + MAX_SYSTEM_PROMPT_LENGTH + " characters");
        }
        if (history == null) {
            throw new ValidationException("history", "must not be null");
        }
        if (tools == null) {
            throw new ValidationException("tools", "must not be null");
        }
        if (enabledMcpServers == null) {
            throw new ValidationException("enabledMcpServers", "must not be null");
        }
        if (sampling == null) {
            throw new ValidationException("sampling", "must not be null");
        }
        if (ownerUserId == null) {
            throw new ValidationException("ownerUserId", "must not be null");
        }
        history = List.copyOf(history);
        tools = List.copyOf(tools);
        enabledMcpServers = List.copyOf(enabledMcpServers);
    }
}
