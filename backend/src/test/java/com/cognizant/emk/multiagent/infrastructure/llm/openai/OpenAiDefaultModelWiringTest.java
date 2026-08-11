package com.cognizant.emk.multiagent.infrastructure.llm.openai;

import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code app.llm.openai.default-model} → {@code spring.ai.openai.chat.options.model}
 * placeholder relay (US-09-002, REQ-LLM-002). The two keys MUST resolve to the
 * same value so operators have a single env var ({@code OPENAI_MODEL}) to override.
 */
@SpringBootTest
class OpenAiDefaultModelWiringTest {

    @Autowired
    private ApplicationProperties properties;

    @Autowired
    private Environment environment;

    @Test
    void default_model_resolves_to_gpt_4o_mini() {
        assertThat(properties.llm().openai().defaultModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void spring_ai_chat_options_model_relays_the_app_property() {
        String relayed = environment.getProperty("spring.ai.openai.chat.options.model");
        assertThat(relayed).isEqualTo(properties.llm().openai().defaultModel());
    }
}
