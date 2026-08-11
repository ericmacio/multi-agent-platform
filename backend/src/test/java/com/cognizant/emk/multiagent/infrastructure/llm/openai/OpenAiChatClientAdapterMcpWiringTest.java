package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.application.chat.ChatMessage;
import com.cognizant.emk.multiagent.application.chat.ChatRequest;
import com.cognizant.emk.multiagent.application.chat.Role;
import com.cognizant.emk.multiagent.application.chat.SamplingParameters;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.infrastructure.mcp.McpToolCallbackResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the MCP-wiring contract: {@code ChatRequest.enabledMcpServers} is
 * routed through {@link McpToolCallbackResolver}, and the resolved
 * {@link ToolCallback}s end up on the {@link Prompt}'s
 * {@link ToolCallingChatOptions}, alongside any static-tool callbacks. This is
 * the last-mile fix for REQ-AGT-009 / REQ-AGT-014 (edits to an agent's MCP
 * list take effect on the very next turn).
 */
class OpenAiChatClientAdapterMcpWiringTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void enabled_mcp_servers_produce_tool_callbacks_on_the_prompt_options() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));

        ToolCallback mcpCallback = fakeMcpCallback("fs__read");
        McpToolCallbackResolver resolver = mock(McpToolCallbackResolver.class);
        when(resolver.resolve(List.of("filesystem"))).thenReturn(List.of(mcpCallback));

        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), resolver);

        adapter.call(new ChatRequest(
                "gpt-4o-mini",
                "sys",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.of(),
                List.of("filesystem"),
                SamplingParameters.none(),
                OWNER));

        ToolCallingChatOptions options = capturedToolOptions(model);
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolCallbacks().get(0).getToolDefinition().name()).isEqualTo("fs__read");
    }

    @Test
    void empty_enabled_mcp_servers_yields_plain_ChatOptions_without_tool_callbacks() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));

        McpToolCallbackResolver resolver = mock(McpToolCallbackResolver.class);
        when(resolver.resolve(List.of())).thenReturn(List.of());

        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), resolver);

        adapter.call(new ChatRequest(
                "gpt-4o-mini",
                "sys",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.of(),
                List.of(),
                SamplingParameters.none(),
                OWNER));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions()).isNotInstanceOf(ToolCallingChatOptions.class);
    }

    @Test
    void tool_and_mcp_callbacks_are_merged_on_the_same_prompt() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));

        FakeTool fakeTool = new FakeTool();
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.resolveBean("FakeTool")).thenReturn(Optional.of(fakeTool));

        ToolCallback braveCallback = fakeMcpCallback("brave__search");
        McpToolCallbackResolver resolver = mock(McpToolCallbackResolver.class);
        when(resolver.resolve(List.of("brave-search"))).thenReturn(List.of(braveCallback));

        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(model, catalog, resolver);

        adapter.call(new ChatRequest(
                "gpt-4o-mini",
                "sys",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.of(new ToolDescriptor("FakeTool", "a fake tool")),
                List.of("brave-search"),
                SamplingParameters.none(),
                OWNER));

        ToolCallingChatOptions options = capturedToolOptions(model);
        assertThat(options.getToolCallbacks())
                .extracting(tc -> tc.getToolDefinition().name())
                .containsExactlyInAnyOrder("doStuff", "brave__search");
    }

    @Test
    void stream_path_also_wires_mcp_callbacks() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(reactor.core.publisher.Flux.empty());

        ToolCallback mcpCallback = fakeMcpCallback("fs__write");
        McpToolCallbackResolver resolver = mock(McpToolCallbackResolver.class);
        when(resolver.resolve(List.of("filesystem"))).thenReturn(List.of(mcpCallback));

        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), resolver);

        adapter.stream(new ChatRequest(
                "gpt-4o-mini",
                "sys",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.of(),
                List.of("filesystem"),
                SamplingParameters.none(),
                OWNER)).blockLast();

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).stream(promptCaptor.capture());
        ToolCallingChatOptions options = (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolCallbacks().get(0).getToolDefinition().name()).isEqualTo("fs__write");
    }

    // ---- helpers ----

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ToolCallingChatOptions capturedToolOptions(ChatModel model) {
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(promptCaptor.capture());
        return (ToolCallingChatOptions) promptCaptor.getValue().getOptions();
    }

    private static ToolCallback fakeMcpCallback(String toolName) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(
                ToolDefinition.builder()
                        .name(toolName)
                        .description("mcp-exposed tool " + toolName)
                        .inputSchema("{}")
                        .build());
        return callback;
    }

    /** Minimal @Tool-annotated bean so the catalog side of the merge is exercised too. */
    static class FakeTool {
        @Tool(description = "a fake tool action used in tests")
        public String doStuff(String input) {
            return "echo: " + input;
        }
    }
}
