package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Interface-level contract test: locks in the {@link LlmChatClient} API surface
 * and verifies a mock implementation can return both a {@link ChatResult} and a
 * reactive {@link Flux} of {@link ChatChunk}s, including the error path.
 *
 * <p>This test is deliberately Mockito-only (no StepVerifier / reactor-test
 * dependency) so US-09-001 ships without adding a test-scope dependency that
 * only becomes load-bearing in US-09-005.
 */
class LlmChatClientContractTest {

    @Test
    void call_returns_the_stubbed_chat_result() {
        LlmChatClient client = mock(LlmChatClient.class);
        ChatRequest request = sampleRequest();
        when(client.call(request)).thenReturn(new ChatResult("hello"));

        ChatResult result = client.call(request);

        assertThat(result.text()).isEqualTo("hello");
    }

    @Test
    void stream_emits_the_stubbed_chunks_then_completes() {
        LlmChatClient client = mock(LlmChatClient.class);
        ChatRequest request = sampleRequest();
        when(client.stream(request)).thenReturn(Flux.just(
                new ChatChunk("Hello"),
                new ChatChunk(", "),
                new ChatChunk("world!")));

        List<String> emitted = client.stream(request)
                .map(ChatChunk::text)
                .collectList()
                .block();

        assertThat(emitted).containsExactly("Hello", ", ", "world!");
    }

    @Test
    void stream_propagates_errors_via_Flux_error() {
        // US-09-003 ships LlmUnavailableException; for the v1 contract we just need
        // to prove the reactive surface signals errors inside the chain rather than
        // throwing synchronously on subscribe — any RuntimeException stands in.
        RuntimeException boom = new RuntimeException("provider down");
        LlmChatClient client = mock(LlmChatClient.class);
        ChatRequest request = sampleRequest();
        when(client.stream(request)).thenReturn(Flux.error(boom));

        AtomicReference<Throwable> captured = new AtomicReference<>();
        client.stream(request).subscribe(chunk -> {}, captured::set);

        assertThat(captured.get()).isSameAs(boom);
    }

    private static ChatRequest sampleRequest() {
        return new ChatRequest(
                "gpt-4o-mini",
                "you are an assistant",
                List.of(new ChatMessage(Role.USER, "hi")),
                List.<ToolDescriptor>of(),
                List.<String>of(),
                SamplingParameters.none(),
                UUID.randomUUID());
    }
}
