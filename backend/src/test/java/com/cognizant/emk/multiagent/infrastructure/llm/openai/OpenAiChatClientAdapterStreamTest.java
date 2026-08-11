package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.application.chat.ChatChunk;
import com.cognizant.emk.multiagent.application.chat.ChatMessage;
import com.cognizant.emk.multiagent.application.chat.ChatRequest;
import com.cognizant.emk.multiagent.application.chat.Role;
import com.cognizant.emk.multiagent.application.chat.SamplingParameters;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import com.cognizant.emk.multiagent.infrastructure.error.LlmUnavailableException;
import com.cognizant.emk.multiagent.infrastructure.mcp.McpToolCallbackResolver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenAiChatClientAdapter#stream(ChatRequest)} (US-09-005).
 *
 * <p>Like {@link OpenAiChatClientAdapterCallTest}, this test substitutes a mock
 * {@link ChatModel} for WireMock because Spring AI 1.1.0's binary incompat with
 * Spring Framework 7 prevents the real {@code OpenAiApi} bean from instantiating.
 * The reactive semantics under test (emission order, error propagation via
 * {@code onErrorMap}, cancellation hook) are unchanged by that substitution.
 */
class OpenAiChatClientAdapterStreamTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void emits_chunks_in_order_and_completes() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                responseWithText("Hello"),
                responseWithText(", "),
                responseWithText("world!")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        StepVerifier.create(adapter.stream(sampleRequest().build()).map(ChatChunk::text))
                .expectNext("Hello", ", ", "world!")
                .verifyComplete();
    }

    @Test
    void emits_empty_text_chunk_when_provider_sends_empty_delta() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                responseWithText(""),
                responseWithText("ok")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        StepVerifier.create(adapter.stream(sampleRequest().build()).map(ChatChunk::text))
                .expectNext("", "ok")
                .verifyComplete();
    }

    @Test
    void emits_empty_text_chunk_when_response_has_no_result() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of())));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        StepVerifier.create(adapter.stream(sampleRequest().build()).map(ChatChunk::text))
                .expectNext("")
                .verifyComplete();
    }

    @Test
    void provider_4xx_at_request_time_signals_LlmUnavailableException() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null)));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        StepVerifier.create(adapter.stream(sampleRequest().build()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(LlmUnavailableException.class);
                    assertThat(err.getMessage()).isEqualTo("openai provider failure: http_4xx 401");
                })
                .verify();
    }

    @Test
    void provider_429_during_stream_does_not_map_to_our_rate_limit() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many", null, null, null)));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        StepVerifier.create(adapter.stream(sampleRequest().build()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(LlmUnavailableException.class);
                    assertThat(err.getMessage()).isEqualTo("openai provider failure: http_429");
                })
                .verify();
    }

    @Test
    void provider_5xx_mid_stream_emits_preceding_chunks_then_errors() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(responseWithText("part-one"), responseWithText("part-two")),
                Flux.error(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", null, null, null))));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        StepVerifier.create(adapter.stream(sampleRequest().build()).map(ChatChunk::text))
                .expectNext("part-one", "part-two")
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(LlmUnavailableException.class);
                    assertThat(err.getMessage()).isEqualTo("openai provider failure: http_5xx 500");
                })
                .verify();
    }

    @Test
    void downstream_cancellation_propagates_upstream_to_the_provider_call() throws Exception {
        // The load-bearing assertion: a downstream cancel propagates all the way up
        // to the Flux returned by ChatModel.stream(), so that in production the
        // underlying HTTP connection to OpenAI is released (REQ-STR-003).
        CompletableFuture<Boolean> upstreamCancelled = new CompletableFuture<>();
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.just(responseWithText("hi"))
                        .concatWith(Flux.<ChatResponse>never())
                        .doOnCancel(() -> upstreamCancelled.complete(true)));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        reactor.core.Disposable subscription = adapter.stream(sampleRequest().build())
                .subscribe();
        // Give the chain a tick to settle into the never-Flux wait state.
        Thread.sleep(50);
        subscription.dispose();

        assertThat(upstreamCancelled).succeedsWithin(Duration.ofSeconds(2)).isEqualTo(true);
    }

    @Test
    void unknown_runtime_exception_in_stream_maps_to_unknown_classification() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("boom")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        StepVerifier.create(adapter.stream(sampleRequest().build()))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(LlmUnavailableException.class);
                    assertThat(err.getMessage()).isEqualTo("openai provider failure: unknown");
                })
                .verify();
    }

    @Test
    void sends_system_prompt_and_history_messages_in_order() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(responseWithText("ok")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        ChatRequest request = sampleRequest()
                .systemPrompt("you are an assistant")
                .history(List.of(
                        new ChatMessage(Role.USER, "a"),
                        new ChatMessage(Role.ASSISTANT, "b")))
                .build();

        adapter.stream(request).blockLast(Duration.ofSeconds(2));

        Prompt captured = capturePrompt(model);
        assertThat(captured.getInstructions()).hasSize(3);
        assertThat(captured.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(captured.getInstructions().get(0).getText()).isEqualTo("you are an assistant");
        assertThat(captured.getInstructions().get(1)).isInstanceOf(UserMessage.class);
        assertThat(captured.getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    void per_agent_model_override_passes_through() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(responseWithText("ok")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        adapter.stream(sampleRequest().model("gpt-4o").build())
                .blockLast(Duration.ofSeconds(2));

        Prompt captured = capturePrompt(model);
        assertThat(captured.getOptions().getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void sampling_parameters_pass_through_when_non_null() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(responseWithText("ok")));
        OpenAiChatClientAdapter adapter = new OpenAiChatClientAdapter(
                model, mock(ToolCatalog.class), McpToolCallbackResolver.noop());

        adapter.stream(sampleRequest()
                .sampling(new SamplingParameters(0.5, 128, 0.95))
                .build())
                .blockLast(Duration.ofSeconds(2));

        Prompt captured = capturePrompt(model);
        assertThat(captured.getOptions().getTemperature()).isEqualTo(0.5);
        assertThat(captured.getOptions().getMaxTokens()).isEqualTo(128);
        assertThat(captured.getOptions().getTopP()).isEqualTo(0.95);
    }

    // ---- helpers ----

    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Prompt capturePrompt(ChatModel model) {
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).stream(captor.capture());
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
