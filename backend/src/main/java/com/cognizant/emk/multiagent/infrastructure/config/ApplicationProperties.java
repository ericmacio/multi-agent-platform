package com.cognizant.emk.multiagent.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed binding of the {@code app.*} configuration tree.
 *
 * <p>One nested record per section. Sub-keys are added by their owning EPIC; only the keys
 * already needed by the EPICs implemented so far are populated here. New EPICs append a
 * nested record to this surface rather than introducing parallel
 * {@code @ConfigurationProperties} classes.
 *
 * <p>{@code @Validated} on the root cascades bean-validation into every nested record marked
 * {@code @Valid} — load-bearing for {@code REQ-AUTH-010} which requires the application to
 * fail fast when {@code JWT_SIGNING_SECRET} is missing or too short to satisfy HS256.
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record ApplicationProperties(
        @Valid Api api,
        @Valid Cors cors,
        @Valid Security security,
        @Valid Aws aws,
        @Valid Mcp mcp,
        @Valid Llm llm,
        @Valid Streaming streaming
) {

    public record Api(String basePath) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Security(@Valid Jwt jwt) {

        public record Jwt(
                Duration lifetime,
                @NotBlank @Size(min = 32) String signingSecret) {}
    }

    /**
     * AWS configuration consumed by the {@code AwsS3Tool} (US-07-003). The region
     * defaults to {@code eu-west-3} in {@code application.yaml} via the
     * {@code ${AWS_REGION:eu-west-3}} placeholder; production deployments override
     * via the {@code AWS_REGION} env var without rebuilding the JAR (REQ-NFR-003 /
     * REQ-DEP-003).
     */
    public record Aws(@NotBlank String region) {}

    /**
     * MCP configuration consumed by EPIC-08. The {@code filesystem} sub-section
     * carries the base directory under which per-user MCP filesystem roots are
     * resolved on demand (REQ-MCP-005); the base value comes from
     * {@code ${MCP_FS_BASE:./var/lib/multi-agent/fs}} so the local default is
     * a relative path writable on a stock Windows laptop, and production
     * deployments override via the {@code MCP_FS_BASE} env var.
     */
    public record Mcp(@Valid Filesystem filesystem) {

        public record Filesystem(@NotBlank String base) {}
    }

    /**
     * LLM configuration consumed by EPIC-09. The {@code openai} sub-section
     * carries the default model name (REQ-LLM-002) read by
     * {@code OpenAiChatClientAdapter} when the per-agent {@code llmModel}
     * override is absent. The value flows from
     * {@code ${OPENAI_MODEL:gpt-4o-mini}}; the same expression is relayed to
     * {@code spring.ai.openai.chat.options.model} so the two sources cannot
     * drift. OpenAI credentials live under {@code spring.ai.openai.api-key}
     * (bound by Spring AI's starter) and are validated on startup by
     * {@code OpenAiConfig} (US-09-002).
     */
    public record Llm(@Valid Openai openai) {

        public record Openai(@NotBlank @Size(max = 64) String defaultModel) {}
    }

    /**
     * Streaming configuration consumed by EPIC-11. The {@code emitterTimeout}
     * caps how long an SSE chat-turn response may stay open before Tomcat
     * fires {@code SseEmitter.onTimeout(...)} and the
     * {@code SendMessageService} subscription is disposed (REQ-STR-003). The
     * default 10-minute value covers comfortably any conceivable LLM turn
     * while still freeing thread / connection resources on stuck streams.
     * Operators override via {@code APP_STREAMING_EMITTER_TIMEOUT} (Spring's
     * relaxed binding) or by editing the deployment yaml.
     */
    public record Streaming(@NotNull Duration emitterTimeout) {}
}
