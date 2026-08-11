package com.cognizant.emk.multiagent.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the application fails fast at startup when {@code JWT_SIGNING_SECRET}
 * is missing or shorter than the 32-byte HS256 minimum (REQ-AUTH-010).
 *
 * <p>Uses {@link ApplicationContextRunner} for a focused, DB-free context — only the
 * properties bean and the validation autoconfig are wired in, which makes the test fast
 * and isolates the binding-validation behaviour we want to assert.
 */
class JwtSigningSecretFailFastTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(EnablePropsConfig.class)
            .withPropertyValues(
                    "app.api.base-path=/api/v1",
                    "app.cors.allowed-origins=http://localhost:5173",
                    "app.security.jwt.lifetime=PT30M");

    @Test
    void valid_secret_allows_startup() {
        runner.withPropertyValues("app.security.jwt.signing-secret=" + "x".repeat(40))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void empty_secret_blocks_startup() {
        runner.withPropertyValues("app.security.jwt.signing-secret=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(stackTraceText(context.getStartupFailure()))
                            .containsIgnoringCase("signingSecret");
                });
    }

    @Test
    void short_secret_blocks_startup() {
        runner.withPropertyValues("app.security.jwt.signing-secret=tooshort")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTraceText(context.getStartupFailure()))
                            .containsIgnoringCase("signingSecret");
                });
    }

    /** Concatenates messages from the entire cause chain so the assertion is robust to wrapping. */
    private static String stackTraceText(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    @Configuration
    @EnableConfigurationProperties(ApplicationProperties.class)
    static class EnablePropsConfig {}
}
