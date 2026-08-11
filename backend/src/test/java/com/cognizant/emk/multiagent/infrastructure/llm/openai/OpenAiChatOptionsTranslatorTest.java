package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.application.chat.ChatMessage;
import com.cognizant.emk.multiagent.application.chat.ChatRequest;
import com.cognizant.emk.multiagent.application.chat.Role;
import com.cognizant.emk.multiagent.application.chat.SamplingParameters;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatOptionsTranslatorTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Test
    void translates_a_full_request_into_a_well_formed_Prompt() {
        ChatRequest request = newRequest()
                .systemPrompt("you are an assistant")
                .history(List.of(
                        new ChatMessage(Role.USER, "first"),
                        new ChatMessage(Role.ASSISTANT, "second"),
                        new ChatMessage(Role.USER, "third")))
                .build();

        Prompt prompt = OpenAiChatOptionsTranslator.toPrompt(request, List.of(), List.of());

        List<Message> messages = prompt.getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("you are an assistant");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("first");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2).getText()).isEqualTo("second");
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(3).getText()).isEqualTo("third");
    }

    @Test
    void translates_sampling_parameters_when_present() {
        ChatRequest request = newRequest()
                .sampling(new SamplingParameters(0.7, 256, 0.9))
                .build();

        ChatOptions options = OpenAiChatOptionsTranslator.toOptions(request, List.of(), List.of());

        assertThat(options.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(options.getTemperature()).isEqualTo(0.7);
        assertThat(options.getMaxTokens()).isEqualTo(256);
        assertThat(options.getTopP()).isEqualTo(0.9);
    }

    @Test
    void omits_sampling_parameters_when_none() {
        ChatRequest request = newRequest().sampling(SamplingParameters.none()).build();

        ChatOptions options = OpenAiChatOptionsTranslator.toOptions(request, List.of(), List.of());

        assertThat(options.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getMaxTokens()).isNull();
        assertThat(options.getTopP()).isNull();
    }

    @Test
    void model_passes_through_verbatim() {
        ChatRequest request = newRequest().model("gpt-4o").build();

        ChatOptions options = OpenAiChatOptionsTranslator.toOptions(request, List.of(), List.of());

        assertThat(options.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void empty_history_yields_only_the_system_message() {
        ChatRequest request = newRequest().history(List.of()).build();

        Prompt prompt = OpenAiChatOptionsTranslator.toPrompt(request, List.of(), List.of());

        assertThat(prompt.getInstructions()).hasSize(1);
        assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
    }

    // ---- helpers ----

    private static Builder newRequest() {
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
