package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.application.chat.ChatMessage;
import com.cognizant.emk.multiagent.application.chat.ChatRequest;
import com.cognizant.emk.multiagent.application.chat.SamplingParameters;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * Translates an application-layer {@link ChatRequest} into a Spring AI
 * {@link Prompt} + {@link ChatOptions} pair.
 *
 * <p>Shared between {@code OpenAiChatClientAdapter.call(...)} (US-09-004) and
 * {@code stream(...)} (US-09-005) so the two paths produce byte-identical
 * upstream requests. The translator is pure-static and has no Spring or HTTP
 * dependencies.
 *
 * <p>Tool wiring: when the caller passes a non-empty {@code toolBeans} list
 * and/or a non-empty {@code mcpCallbacks} list, the translator builds an
 * {@link OpenAiChatOptions} with the corresponding {@link ToolCallback}s
 * attached (REQ-TOOL-001/-005, REQ-AGT-009). Spring AI's {@code OpenAiChatModel}
 * then advertises the tool definitions to the LLM and dispatches incoming tool
 * calls back to the bean's {@code @Tool}-annotated methods or the MCP server.
 * With both lists empty the translator falls back to a plain
 * {@link ChatOptions} — no tool advertisement, no overhead.
 */
final class OpenAiChatOptionsTranslator {

    private OpenAiChatOptionsTranslator() {}

    static Prompt toPrompt(ChatRequest request, List<Object> toolBeans, List<ToolCallback> mcpCallbacks) {
        List<Message> messages = new ArrayList<>(1 + request.history().size());
        messages.add(new SystemMessage(request.systemPrompt()));
        for (ChatMessage m : request.history()) {
            messages.add(toSpringAiMessage(m));
        }
        return new Prompt(messages, toOptions(request, toolBeans, mcpCallbacks));
    }

    static ChatOptions toOptions(ChatRequest request, List<Object> toolBeans, List<ToolCallback> mcpCallbacks) {
        SamplingParameters s = request.sampling();
        boolean hasBeans = toolBeans != null && !toolBeans.isEmpty();
        boolean hasMcp = mcpCallbacks != null && !mcpCallbacks.isEmpty();

        if (!hasBeans && !hasMcp) {
            ChatOptions.Builder b = ChatOptions.builder().model(request.model());
            if (s.temperature() != null) {
                b.temperature(s.temperature());
            }
            if (s.maxOutputTokens() != null) {
                b.maxTokens(s.maxOutputTokens());
            }
            if (s.topP() != null) {
                b.topP(s.topP());
            }
            return b.build();
        }

        List<ToolCallback> merged = new ArrayList<>();
        if (hasBeans) {
            for (ToolCallback tc : ToolCallbacks.from(toolBeans.toArray())) {
                merged.add(tc);
            }
        }
        if (hasMcp) {
            merged.addAll(mcpCallbacks);
        }
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder()
                .model(request.model())
                .toolCallbacks(merged.toArray(new ToolCallback[0]));
        if (s.temperature() != null) {
            b.temperature(s.temperature());
        }
        if (s.maxOutputTokens() != null) {
            b.maxTokens(s.maxOutputTokens());
        }
        if (s.topP() != null) {
            b.topP(s.topP());
        }
        return b.build();
    }

    private static Message toSpringAiMessage(ChatMessage m) {
        return switch (m.role()) {
            case USER -> new UserMessage(m.content());
            case ASSISTANT -> new AssistantMessage(m.content());
        };
    }
}
