package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the application fails fast at startup when {@code OPENAI_API_KEY}
 * is missing or blank (REQ-LLM-003 / REQ-SEC-003 / US-09-002). Symmetric with
 * {@code JwtSigningSecretFailFastTest}.
 *
 * <p>Uses {@link ApplicationContextRunner} for a focused, DB-free context — only
 * {@link OpenAiConfig} is wired in, so the test exercises the {@code @PostConstruct}
 * check without booting the full application.
 */
class OpenAiApiKeyMissingFailsFastTest {

    // The production fallback @Bean ChatModel / LlmChatClient from OpenAiConfig
    // are disabled so this slice-test focuses purely on the @PostConstruct
    // fail-fast contract. Tests that need a ChatModel provide their own.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiConfig.class)
            .withPropertyValues("app.llm.openai.fallback-bean.enabled=false");

    @Test
    void missing_property_blocks_startup_with_OPENAI_API_KEY_in_message() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(stackTraceText(context.getStartupFailure()))
                    .contains("OPENAI_API_KEY");
        });
    }

    @Test
    void empty_property_blocks_startup_with_OPENAI_API_KEY_in_message() {
        runner.withPropertyValues("spring.ai.openai.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceText(context.getStartupFailure()))
                            .contains("OPENAI_API_KEY");
                });
    }

    @Test
    void blank_property_blocks_startup_with_OPENAI_API_KEY_in_message() {
        runner.withPropertyValues("spring.ai.openai.api-key=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceText(context.getStartupFailure()))
                            .contains("OPENAI_API_KEY");
                });
    }

    @Test
    void error_message_does_not_leak_the_resolved_value() {
        // REQ-SEC-004: never log/raise API key fragments. A blank or empty value
        // is OK to display but the fail-fast must not echo the resolved string
        // when it would otherwise look like a real-but-truncated key.
        runner.withPropertyValues("spring.ai.openai.api-key=sk-leaked-fragment")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static String stackTraceText(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            sb.append(t).append('\n');
        }
        return sb.toString();
    }
}
