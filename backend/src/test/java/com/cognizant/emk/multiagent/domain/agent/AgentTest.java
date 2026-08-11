package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.user.UserId;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTest {

    private static final OffsetDateTime CREATED =
            OffsetDateTime.of(2026, 5, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime UPDATED =
            OffsetDateTime.of(2026, 5, 2, 10, 0, 0, 0, ZoneOffset.UTC);

    private static Agent sample() {
        return new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("research-bot"),
                "Searches the web",
                "You are a research bot.",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of("AwsS3Tool"),
                List.of("brave-search"),
                Team.EMPTY,
                CREATED,
                UPDATED);
    }

    // ------- happy path / mutation -------

    @Test
    void with_replacement_preserves_id_owner_created_and_bumps_updated_at() {
        Agent original = sample();
        OffsetDateTime now = OffsetDateTime.of(2026, 6, 1, 12, 0, 0, 0, ZoneOffset.UTC);

        Agent replaced = original.withReplacement(
                new AgentName("trader-bot"),
                "Buys and sells",
                "You are a trading bot.",
                new MemorySize(24),
                new SamplingParams("gpt-4o", 0.7, 1024, 0.95),
                List.of(),
                List.of(),
                Team.EMPTY,
                now);

        assertThat(replaced.id()).isEqualTo(original.id());
        assertThat(replaced.ownerId()).isEqualTo(original.ownerId());
        assertThat(replaced.createdAt()).isEqualTo(original.createdAt());

        assertThat(replaced.name()).isEqualTo(new AgentName("trader-bot"));
        assertThat(replaced.description()).isEqualTo("Buys and sells");
        assertThat(replaced.systemPrompt()).isEqualTo("You are a trading bot.");
        assertThat(replaced.memorySize().value()).isEqualTo(24);
        assertThat(replaced.samplingParams().llmModel()).isEqualTo("gpt-4o");
        assertThat(replaced.tools()).isEmpty();
        assertThat(replaced.enabledMcpServers()).isEmpty();
        assertThat(replaced.team().members()).isEmpty();
        assertThat(replaced.updatedAt()).isEqualTo(now);
    }

    @Test
    void with_replacement_rejects_null_now() {
        Agent original = sample();
        assertThatNullPointerException().isThrownBy(() -> original.withReplacement(
                new AgentName("x"), "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY, null));
    }

    // ------- canonical-constructor validation -------

    @Test
    void rejects_null_required_references() {
        AgentId id = new AgentId(UUID.randomUUID());
        UserId owner = new UserId(UUID.randomUUID());
        AgentName name = new AgentName("x");
        assertThatNullPointerException().isThrownBy(() -> new Agent(
                null, owner, name, "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY, CREATED, UPDATED));
        assertThatNullPointerException().isThrownBy(() -> new Agent(
                id, null, name, "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY, CREATED, UPDATED));
        assertThatNullPointerException().isThrownBy(() -> new Agent(
                id, owner, null, "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY, CREATED, UPDATED));
    }

    @Test
    void rejects_blank_description_with_field_description() {
        assertThatThrownBy(() -> new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                "   ",
                "s",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY, CREATED, UPDATED))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("description"));
    }

    @Test
    void rejects_over_length_description_with_field_description() {
        String over = "a".repeat(1025);
        assertThatThrownBy(() -> new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                over,
                "s",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY, CREATED, UPDATED))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("description");
                    assertThat(ex.getMessage()).contains("1024");
                });
    }

    @Test
    void rejects_over_length_system_prompt_with_field_system_prompt() {
        String over = "a".repeat(1025);
        assertThatThrownBy(() -> new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                "d",
                over,
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY, CREATED, UPDATED))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("systemPrompt"));
    }

    @Test
    void rejects_duplicate_tool_names_with_field_tools() {
        assertThatThrownBy(() -> new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                "d",
                "s",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of("S3", "S3"),
                List.of(),
                Team.EMPTY,
                CREATED, UPDATED))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("tools"));
    }

    @Test
    void rejects_blank_tool_name_with_field_tools() {
        assertThatThrownBy(() -> new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                "d",
                "s",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of(" "),
                List.of(),
                Team.EMPTY,
                CREATED, UPDATED))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("tools"));
    }

    @Test
    void rejects_over_length_tool_name_with_field_tools() {
        String over = "a".repeat(65);
        assertThatThrownBy(() -> new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                "d",
                "s",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of(over),
                List.of(),
                Team.EMPTY,
                CREATED, UPDATED))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("tools");
                    assertThat(ex.getMessage()).contains("64");
                });
    }

    @Test
    void rejects_duplicate_mcp_server_with_field_enabled_mcp_servers() {
        assertThatThrownBy(() -> new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                "d",
                "s",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                List.of(),
                List.of("brave-search", "brave-search"),
                Team.EMPTY,
                CREATED, UPDATED))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("enabledMcpServers"));
    }

    @Test
    void null_tools_or_mcp_lists_normalize_to_empty_unmodifiable_lists() {
        Agent a = new Agent(
                new AgentId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()),
                new AgentName("x"),
                "d",
                "s",
                MemorySize.DEFAULT,
                SamplingParams.DEFAULTS,
                null,
                null,
                Team.EMPTY,
                CREATED, UPDATED);
        assertThat(a.tools()).isEmpty();
        assertThat(a.enabledMcpServers()).isEmpty();
    }
}
