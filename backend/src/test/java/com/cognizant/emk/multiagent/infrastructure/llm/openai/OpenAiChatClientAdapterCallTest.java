package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.application.chat.ChatMessage;
import com.cognizant.emk.multiagent.application.chat.ChatRequest;
import com.cognizant.emk.multiagent.application.chat.ChatResult;
import com.cognizant.emk.multiagent.application.chat.Role;
import com.cognizant.emk.multiagent.application.chat.SamplingParameters;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import com.cognizant.emk.multiagent.infrastructure.mcp.McpToolCallbackResolver;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenAiChatClientAdapter#call(ChatRequest)} (US-09-004).
 *
 * <p>The story spec called for WireMock-based integration tests, but Spring AI
 * 1.1.0 has a binary incompatibility with Spring Framework 7
 * ({@code HttpHeaders.addAll(MultiValueMap)} signature change) that prevents
 * the {@code OpenAiApi} bean from being instantiated. The pragmatic substitute
 * is to stub {@link ChatModel} directly and inspect the {@link Prompt}
 * argument — this gives strictly more coverage of the adapter's translation
 * logic than HTTP-level WireMock assertions would. See DESIGN-CHOICES.md.
 */
class OpenAiChatClientAdapterCallTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void returns_assistant_text_from_chat_response() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("hello"));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        ChatResult result = adapter.call(sampleRequest().build());

        assertThat(result.text()).isEqualTo("hello");
    }

    @Test
    void returns_empty_string_when_assistant_text_is_null() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage((String) null)))));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThat(adapter.call(sampleRequest().build()).text()).isEmpty();
    }

    @Test
    void returns_empty_string_when_response_has_no_result() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThat(adapter.call(sampleRequest().build()).text()).isEmpty();
    }

    @Test
    void provider_4xx_maps_to_LlmUnavailableException_with_http_4xx_classification() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThatThrownBy(() -> adapter.call(sampleRequest().build()))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("openai provider failure: http_4xx 401");
                    assertThat(ex.getCause()).isInstanceOf(HttpClientErrorException.class);
                });
    }

    @Test
    void provider_429_maps_to_http_429_not_to_our_rate_limit() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many", null, null, null));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThatThrownBy(() -> adapter.call(sampleRequest().build()))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("openai provider failure: http_429"));
    }

    @Test
    void provider_5xx_maps_to_LlmUnavailableException_with_http_5xx_classification() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", null, null, null));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThatThrownBy(() -> adapter.call(sampleRequest().build()))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("openai provider failure: http_5xx 500"));
    }

    @Test
    void connection_refused_maps_to_connection_refused_classification() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new ResourceAccessException(
                "io error", new ConnectException("Connection refused")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThatThrownBy(() -> adapter.call(sampleRequest().build()))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("openai provider failure: connection_refused"));
    }

    @Test
    void timeout_maps_to_timeout_classification() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new ResourceAccessException(
                "io error", new SocketTimeoutException("read timed out")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThatThrownBy(() -> adapter.call(sampleRequest().build()))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("openai provider failure: timeout"));
    }

    @Test
    void unknown_runtime_exception_maps_to_unknown_classification() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        assertThatThrownBy(() -> adapter.call(sampleRequest().build()))
                .isInstanceOfSatisfying(LlmUnavailableException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("openai provider failure: unknown"));
    }

    @Test
    void sends_system_prompt_and_history_messages_in_order() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        ChatRequest request = sampleRequest()
                .systemPrompt("you are an assistant")
                .history(List.of(
                        new ChatMessage(Role.USER, "a"),
                        new ChatMessage(Role.ASSISTANT, "b"),
                        new ChatMessage(Role.USER, "c")))
                .build();

        adapter.call(request);

        Prompt captured = capturePrompt(model);
        assertThat(captured.getInstructions()).hasSize(4);
        assertThat(captured.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(captured.getInstructions().get(0).getText()).isEqualTo("you are an assistant");
        assertThat(captured.getInstructions().get(1)).isInstanceOf(UserMessage.class);
        assertThat(captured.getInstructions().get(1).getText()).isEqualTo("a");
        assertThat(captured.getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(captured.getInstructions().get(2).getText()).isEqualTo("b");
        assertThat(captured.getInstructions().get(3)).isInstanceOf(UserMessage.class);
        assertThat(captured.getInstructions().get(3).getText()).isEqualTo("c");
    }

    @Test
    void per_agent_model_override_passes_through() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        adapter.call(sampleRequest().model("gpt-4o").build());

        Prompt captured = capturePrompt(model);
        assertThat(captured.getOptions().getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void sampling_parameters_pass_through_when_non_null() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        adapter.call(sampleRequest()
                .sampling(new SamplingParameters(0.7, 256, 0.9))
                .build());

        Prompt captured = capturePrompt(model);
        assertThat(captured.getOptions().getTemperature()).isEqualTo(0.7);
        assertThat(captured.getOptions().getMaxTokens()).isEqualTo(256);
        assertThat(captured.getOptions().getTopP()).isEqualTo(0.9);
    }

    @Test
    void omits_sampling_parameters_when_none() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("ok"));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        adapter.call(sampleRequest().sampling(SamplingParameters.none()).build());

        Prompt captured = capturePrompt(model);
        assertThat(captured.getOptions().getTemperature()).isNull();
        assertThat(captured.getOptions().getMaxTokens()).isNull();
        assertThat(captured.getOptions().getTopP()).isNull();
    }

    // ---- helpers ----

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Prompt capturePrompt(ChatModel model) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(captor.capture());
        return captor.getValue();
    }

    private static Builder sampleRequest() {
        return new Builder();
    }

    private static final class Builder {
        private String model = "gpt-4o-mini";
        private String systemPrompt = "system";
        private List<ChatMessage> history = List.of(new ChatMessage(Role.USER, "hi"));
        private List<ToolDescriptor> tools = List.of();
        private List<String> enabledMcpServers = List.of();
        private SamplingParameters sampling = SamplingParameters.none();
        private UUID ownerUserId = OWNER;

        Builder model(String v) { this.model = v; return this; }
        Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        Builder history(List<ChatMessage> v) { this.history = v; return this; }
        Builder sampling(SamplingParameters v) { this.sampling = v; return this; }

        ChatRequest build() {
            return new ChatRequest(
                    model, systemPrompt, history, tools, enabledMcpServers, sampling, ownerUserId);
        }
    }
}
