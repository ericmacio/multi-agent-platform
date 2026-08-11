package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.application.chat.ChatChunk;
import com.cognizant.emk.multiagent.application.chat.ChatRequest;
import com.cognizant.emk.multiagent.application.chat.ChatResult;
import com.cognizant.emk.multiagent.application.chat.LlmChatClient;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import com.cognizant.emk.multiagent.infrastructure.mcp.McpToolCallbackResolver;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * OpenAI implementation of {@link LlmChatClient} (design §12, EPIC-09).
 *
 * <p>Sits behind Spring AI's {@link ChatModel} bean (auto-configured by
 * {@code spring-ai-starter-model-openai}). The adapter owns translation of
 * application-layer {@link ChatRequest} into Spring AI's {@link Prompt} +
 * {@code ChatOptions} via {@link OpenAiChatOptionsTranslator}, and translation
 * of every provider failure into {@link LlmUnavailableException} via
 * {@link OpenAiErrorMapper} (REQ-LLM-005).
 *
 * <p>Tool wiring per agent: for every {@link ToolDescriptor} in
 * {@link ChatRequest#tools()}, the adapter resolves the underlying
 * {@code @ToolGroup} bean from the {@link ToolCatalog} and attaches it via
 * {@link OpenAiChatOptionsTranslator} as a Spring AI {@code ToolCallback}.
 * Spring AI then advertises the {@code @Tool}-annotated methods to the LLM
 * and dispatches incoming tool calls back to the bean.
 *
 * <p>MCP wiring per agent: {@code agent.enabledMcpServers} is resolved via
 * {@link McpToolCallbackResolver} to the subset of Spring AI MCP
 * {@link ToolCallback}s contributed by the enabled connections
 * (REQ-AGT-009 / REQ-MCP-004). Those callbacks are merged with the static
 * tool-catalog callbacks and attached to {@code OpenAiChatOptions} through
 * {@link OpenAiChatOptionsTranslator}, so the LLM sees the union of tools and
 * MCP-exposed capabilities on every turn — REQ-AGT-014 stays live because the
 * agent is re-fetched by {@code ChatRequestBuilder} on each call.
 *
 * <p>{@link ConditionalOnBean} on {@link ChatModel}: in test profiles the
 * OpenAI autoconfig is excluded (Spring AI 1.1.0 / Spring Framework 7 binary
 * incompat) and no {@code ChatModel} bean exists. Without the conditional,
 * every {@code @SpringBootTest} in the suite would fail to refresh because
 * the adapter cannot satisfy its constructor argument. Tests that need an
 * {@code LlmChatClient} bean provide their own {@code ChatModel} bean via
 * {@code @TestConfiguration}.
 */
@Component
@ConditionalOnBean(ChatModel.class)
public class OpenAiChatClientAdapter implements LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClientAdapter.class);

    private final ChatModel chatModel;
    private final ToolCatalog toolCatalog;
    private final McpToolCallbackResolver mcpToolCallbackResolver;

    public OpenAiChatClientAdapter(
            ChatModel chatModel,
            ToolCatalog toolCatalog,
            McpToolCallbackResolver mcpToolCallbackResolver) {
        this.chatModel = chatModel;
        this.toolCatalog = toolCatalog;
        this.mcpToolCallbackResolver = mcpToolCallbackResolver;
    }

    @Override
    public ChatResult call(ChatRequest request) {
        Prompt prompt = OpenAiChatOptionsTranslator.toPrompt(
                request, resolveToolBeans(request), resolveMcpCallbacks(request));
        try {
            ChatResponse response = chatModel.call(prompt);
            return new ChatResult(extractContent(response));
        } catch (RuntimeException t) {
            String classification = OpenAiErrorMapper.translate(t);
            log.warn("openai provider call failed: {} ({})", classification, t.getClass().getName());
            throw new LlmUnavailableException(classification, t);
        }
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        Prompt prompt = OpenAiChatOptionsTranslator.toPrompt(
                request, resolveToolBeans(request), resolveMcpCallbacks(request));
        return chatModel.stream(prompt)
                .map(response -> new ChatChunk(extractContent(response)))
                .onErrorMap(t -> {
                    String classification = OpenAiErrorMapper.translate(t);
                    log.warn("openai stream failed: {} ({})", classification, t.getClass().getName());
                    return new LlmUnavailableException(classification, t);
                })
                .doOnCancel(() -> log.debug("openai stream cancelled by downstream"));
    }

    private List<ToolCallback> resolveMcpCallbacks(ChatRequest request) {
        return mcpToolCallbackResolver.resolve(request.enabledMcpServers());
    }

    private List<Object> resolveToolBeans(ChatRequest request) {
        if (request.tools().isEmpty()) {
            return List.of();
        }
        List<Object> beans = new ArrayList<>(request.tools().size());
        for (ToolDescriptor descriptor : request.tools()) {
            toolCatalog.resolveBean(descriptor.name()).ifPresentOrElse(
                    beans::add,
                    () -> log.warn(
                            "agent references tool '{}' but no @ToolGroup bean is registered "
                                    + "under that name — skipping",
                            descriptor.name()));
        }
        return beans;
    }

    private static String extractContent(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return "";
        }
        AssistantMessage out = response.getResult().getOutput();
        if (out == null) {
            return "";
        }
        String text = out.getText();
        return text == null ? "" : text;
    }
}
