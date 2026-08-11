package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.application.chat.LlmChatClient;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.infrastructure.mcp.McpToolCallbackResolver;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * OpenAI-specific Spring configuration (design §12, US-09-002).
 *
 * <p>Owns the startup fail-fast check on {@code OPENAI_API_KEY} (REQ-LLM-003 /
 * REQ-SEC-003) AND a deterministic fallback for the OpenAI {@link ChatModel}
 * bean.
 *
 * <p><strong>Why an explicit ChatModel fallback bean here:</strong> Spring AI
 * 2.0.0-M4 was published against Spring Boot 4.1.0-M2; the project runs on
 * Spring Boot 4.0.6. In some environments the OpenAI auto-config silently
 * fails to register the {@code ChatModel} bean (or registers it too late for
 * {@code @ConditionalOnBean(ChatModel.class)} on
 * {@link OpenAiChatClientAdapter} to match), so {@code Optional<LlmChatClient>}
 * in {@code SendMessageService} is empty and chat sends fail with
 * {@code IllegalStateException("LLM provider is not configured for this
 * environment")}.
 *
 * <p>{@link #fallbackOpenAiChatModel(String, String)} builds an
 * {@link OpenAiChatModel} via its public builder — the same construction path
 * the auto-config takes, minus the optional collaborators we do not need in
 * v1 (custom retry / observation / tool-execution predicate). It is named
 * {@code fallbackOpenAiChatModel} so it never collides with the
 * auto-config's {@code openAiChatModel} bean name (Spring's bean-overriding
 * check fires before {@code @ConditionalOnMissingBean} when names match).
 * Gated by {@link ConditionalOnMissingBean} on {@link ChatModel} so a real
 * auto-config bean (or a test-supplied mock) always wins; gated by
 * {@link ConditionalOnProperty} on {@code app.llm.openai.fallback-bean.enabled}
 * so test contexts can disable the production fallback wholesale.
 *
 * <p>{@link #fallbackOpenAiLlmChatClient(ChatModel)} bypasses
 * {@code @ConditionalOnBean(ChatModel.class)} timing on
 * {@link OpenAiChatClientAdapter}: whichever {@link ChatModel} bean made it
 * into the context (auto-config's or ours), this @{@code @Bean} guarantees
 * {@code SendMessageService} gets a non-empty {@code Optional<LlmChatClient>}.
 */
@Configuration
public class OpenAiConfig {

    private static final String API_KEY_PROPERTY = "spring.ai.openai.api-key";

    private final Environment environment;

    public OpenAiConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verifyOpenAiKeyPresentOnStartup() {
        String key = environment.getProperty(API_KEY_PROPERTY);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY environment variable is missing or empty; cannot start "
                            + "(REQ-LLM-003 / REQ-SEC-003).");
        }
    }

    /**
     * Return type is the concrete {@link OpenAiChatModel} (not the
     * {@link ChatModel} interface) so the Spring AI auto-config's
     * {@code @ConditionalOnMissingBean} — which defaults to the return type
     * of its own {@code @Bean openAiChatModel()} method (also
     * {@link OpenAiChatModel}) — recognises our bean and backs off. With a
     * {@code ChatModel} return type the auto-config would not see ours,
     * leaving two {@code ChatModel} beans in the context and breaking the
     * single-bean injection in {@code fallbackOpenAiLlmChatClient}.
     */
    @Bean
    @ConditionalOnProperty(
            name = "app.llm.openai.fallback-bean.enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(ChatModel.class)
    OpenAiChatModel fallbackOpenAiChatModel(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${app.llm.openai.default-model:gpt-4o-mini}") String defaultModel) {
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).build();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(defaultModel).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.llm.openai.fallback-bean.enabled",
            havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(LlmChatClient.class)
    LlmChatClient fallbackOpenAiLlmChatClient(
            ChatModel chatModel,
            ToolCatalog toolCatalog,
            McpToolCallbackResolver mcpToolCallbackResolver) {
        return new OpenAiChatClientAdapter(chatModel, toolCatalog, mcpToolCallbackResolver);
    }
}
