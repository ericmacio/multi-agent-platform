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
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the tool-wiring contract added when EPIC-11's catalog → adapter
 * resolution was implemented. A {@link ChatRequest} that carries
 * {@link ToolDescriptor}s ends up with the corresponding tool callbacks on the
 * {@link Prompt}'s options, so Spring AI's {@code OpenAiChatModel} advertises
 * the tools to the LLM and routes incoming tool calls back to the bean.
 */
class OpenAiChatClientAdapterToolWiringTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void resolves_tool_beans_from_catalog_and_attaches_callbacks_to_prompt() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));

        FakeTool fakeTool = new FakeTool();
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.resolveBean("FakeTool")).thenReturn(Optional.of(fakeTool));

        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, catalog, McpToolCallbackResolver.noop());

        ChatRequest request = new ChatRequest(
                "gpt-4o-mini",
                "you are a tester",
                List.of(new ChatMessage(Role.USER, "use the tool")),
                List.of(new ToolDescriptor("FakeTool", "a fake tool for the test")),
                List.of(),
                SamplingParameters.none(),
                OWNER);

        adapter.call(request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(promptCaptor.capture());
        var options = promptCaptor.getValue().getOptions();
        assertThat(options).isInstanceOf(ToolCallingChatOptions.class);
        ToolCallingChatOptions tco = (ToolCallingChatOptions) options;
        assertThat(tco.getToolCallbacks()).hasSize(1);
        assertThat(tco.getToolCallbacks().get(0).getToolDefinition().name()).isEqualTo("doStuff");
    }

    @Test
    void empty_tools_list_yields_plain_ChatOptions_without_tool_callbacks() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));
        ToolCatalog catalog = mock(ToolCatalog.class);

        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, catalog, McpToolCallbackResolver.noop());
        adapter.call(requestWithNoTools());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(promptCaptor.capture());
        var options = promptCaptor.getValue().getOptions();
        // Plain ChatOptions, not a ToolCallingChatOptions, when no tools are attached.
        assertThat(options).isNotInstanceOf(ToolCallingChatOptions.class);
    }

    @Test
    void unknown_tool_name_in_request_is_skipped_with_a_log_warning() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));
        ToolCatalog catalog = mock(ToolCatalog.class);
        when(catalog.resolveBean("Missing")).thenReturn(Optional.empty());

        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, catalog, McpToolCallbackResolver.noop());

        ChatRequest request = new ChatRequest(
                "gpt-4o-mini",
                "sys",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.of(new ToolDescriptor("Missing", "no bean for this tool")),
                List.of(),
                SamplingParameters.none(),
                OWNER);

        // Should not throw — unknown names are skipped defensively.
        adapter.call(request);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(promptCaptor.capture());
        // No callbacks because the only requested tool was unresolved.
        assertThat(promptCaptor.getValue().getOptions())
                .isNotInstanceOf(ToolCallingChatOptions.class);
    }

    // ---- helpers ----

    private static ChatRequest requestWithNoTools() {
        return new ChatRequest(
                "gpt-4o-mini",
                "sys",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.of(),
                List.of(),
                SamplingParameters.none(),
                OWNER);
    }

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** Minimal @Tool-annotated bean for the test. */
    static class FakeTool {
        @Tool(description = "a fake tool action used in tests")
        public String doStuff(String input) {
            return "echo: " + input;
        }
    }
}
