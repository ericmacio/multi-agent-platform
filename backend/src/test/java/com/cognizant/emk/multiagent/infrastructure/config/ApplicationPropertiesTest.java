package com.cognizant.emk.multiagent.infrastructure.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the {@code app.*} keys defined in {@code application.yaml} are bound. */
@SpringBootTest
class ApplicationPropertiesTest {

    @Autowired
    private ApplicationProperties properties;

    @Test
    void api_base_path_is_bound() {
        assertThat(properties.api().basePath()).isEqualTo("/api/v1");
    }

    @Test
    void cors_allowed_origins_are_bound() {
        assertThat(properties.cors().allowedOrigins())
                .containsExactly("http://localhost:5173");
    }

    @Test
    void jwt_lifetime_is_bound() {
        assertThat(properties.security().jwt().lifetime()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aws_region_defaults_to_eu_west_3_when_env_var_is_absent() {
        // The test application.yaml uses ${AWS_REGION:eu-west-3} so the default
        // surfaces unless a test sets the env var explicitly.
        assertThat(properties.aws().region()).isEqualTo("eu-west-3");
    }

    @Test
    void mcp_filesystem_base_defaults_to_the_relative_path_when_env_var_is_absent() {
        // The test application.yaml uses ${MCP_FS_BASE:./var/lib/multi-agent/fs} so
        // the relative default surfaces unless a test sets the env var explicitly.
        assertThat(properties.mcp().filesystem().base()).isEqualTo("./var/lib/multi-agent/fs");
    }

    @Test
    void llm_openai_default_model_is_bound() {
        // The test application.yaml hard-codes the v1 default (gpt-4o-mini) so it
        // never depends on OPENAI_MODEL being set in the test environment.
        assertThat(properties.llm().openai().defaultModel()).isEqualTo("gpt-4o-mini");
    }
}
