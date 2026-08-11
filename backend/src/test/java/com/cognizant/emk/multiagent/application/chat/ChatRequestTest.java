package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRequestTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void accepts_a_fully_populated_request() {
        ChatRequest r = newRequest();
        assertThat(r.model()).isEqualTo("gpt-4o-mini");
        assertThat(r.systemPrompt()).isEqualTo("you are an assistant");
        assertThat(r.history()).hasSize(1);
        assertThat(r.tools()).hasSize(1);
        assertThat(r.enabledMcpServers()).containsExactly("brave-search");
        assertThat(r.sampling()).isEqualTo(SamplingParameters.none());
        assertThat(r.ownerUserId()).isEqualTo(OWNER);
    }

    @Test
    void rejects_null_or_blank_model() {
        assertField(() -> newBuilder().model(null).build(), "model");
        assertField(() -> newBuilder().model("   ").build(), "model");
    }

    @Test
    void rejects_over_64_char_model() {
        assertField(() -> newBuilder().model("x".repeat(65)).build(), "model");
    }

    @Test
    void rejects_null_or_blank_systemPrompt() {
        assertField(() -> newBuilder().systemPrompt(null).build(), "systemPrompt");
        assertField(() -> newBuilder().systemPrompt("   ").build(), "systemPrompt");
    }

    @Test
    void rejects_over_1024_char_systemPrompt() {
        assertField(() -> newBuilder().systemPrompt("x".repeat(1025)).build(), "systemPrompt");
    }

    @Test
    void rejects_null_history() {
        assertField(() -> newBuilder().history(null).build(), "history");
    }

    @Test
    void rejects_null_tools() {
        assertField(() -> newBuilder().tools(null).build(), "tools");
    }

    @Test
    void rejects_null_enabledMcpServers() {
        assertField(() -> newBuilder().enabledMcpServers(null).build(), "enabledMcpServers");
    }

    @Test
    void rejects_null_sampling() {
        assertField(() -> newBuilder().sampling(null).build(), "sampling");
    }

    @Test
    void rejects_null_ownerUserId() {
        assertField(() -> newBuilder().ownerUserId(null).build(), "ownerUserId");
    }

    @Test
    void defensively_copies_history_so_external_mutation_is_invisible() {
        List<ChatMessage> source = new ArrayList<>();
        source.add(new ChatMessage(Role.USER, "first"));
        ChatRequest r = newBuilder().history(source).build();

        source.add(new ChatMessage(Role.ASSISTANT, "second"));

        assertThat(r.history()).hasSize(1);
        assertThat(r.history().get(0).content()).isEqualTo("first");
    }

    @Test
    void defensively_copies_tools_and_mcp_lists() {
        List<ToolDescriptor> tools = new ArrayList<>();
        tools.add(new ToolDescriptor("AwsS3Tool", "S3 access"));
        List<String> mcp = new ArrayList<>(List.of("brave-search"));
        ChatRequest r = newBuilder().tools(tools).enabledMcpServers(mcp).build();

        tools.clear();
        mcp.clear();

        assertThat(r.tools()).hasSize(1);
        assertThat(r.enabledMcpServers()).containsExactly("brave-search");
    }

    @Test
    void copied_collections_are_unmodifiable() {
        ChatRequest r = newRequest();
        assertThatThrownBy(() -> r.history().add(new ChatMessage(Role.USER, "x")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> r.tools().add(new ToolDescriptor("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> r.enabledMcpServers().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- helpers ----

    private static ChatRequest newRequest() {
        return newBuilder().build();
    }

    private static Builder newBuilder() {
        return new Builder()
                .model("gpt-4o-mini")
                .systemPrompt("you are an assistant")
                .history(List.of(new ChatMessage(Role.USER, "hi")))
                .tools(List.of(new ToolDescriptor("AwsS3Tool", "S3 access")))
                .enabledMcpServers(List.of("brave-search"))
                .sampling(SamplingParameters.none())
                .ownerUserId(OWNER);
    }

    private static void assertField(ThrowingCallable call, String field) {
        assertThatThrownBy(call::call)
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue(field));
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call();
    }

    private static final class Builder {
        private String model;
        private String systemPrompt;
        private List<ChatMessage> history;
        private List<ToolDescriptor> tools;
        private List<String> enabledMcpServers;
        private SamplingParameters sampling;
        private UUID ownerUserId;

        Builder model(String v) { this.model = v; return this; }
        Builder systemPrompt(String v) { this.systemPrompt = v; return this; }
        Builder history(List<ChatMessage> v) { this.history = v; return this; }
        Builder tools(List<ToolDescriptor> v) { this.tools = v; return this; }
        Builder enabledMcpServers(List<String> v) { this.enabledMcpServers = v; return this; }
        Builder sampling(SamplingParameters v) { this.sampling = v; return this; }
        Builder ownerUserId(UUID v) { this.ownerUserId = v; return this; }

        ChatRequest build() {
            return new ChatRequest(
                    model, systemPrompt, history, tools, enabledMcpServers, sampling, ownerUserId);
        }
    }
}
