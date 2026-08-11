package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Positive counterpart of {@link OpenAiApiKeyMissingFailsFastTest} — verifies that
 * a present, non-blank {@code spring.ai.openai.api-key} property lets the context
 * boot and the {@link OpenAiConfig} bean is wired (US-09-002).
 */
class OpenAiApiKeyPresentBootsFineTest {

    // Same slice-test scoping as OpenAiApiKeyMissingFailsFastTest: the
    // production fallback @Beans are disabled here so the assertion focuses
    // on the @PostConstruct gate.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiConfig.class)
            .withPropertyValues("app.llm.openai.fallback-bean.enabled=false");

    @Test
    void context_loads_when_api_key_is_present() {
        runner.withPropertyValues("spring.ai.openai.api-key=test-openai-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OpenAiConfig.class);
                });
    }
}
