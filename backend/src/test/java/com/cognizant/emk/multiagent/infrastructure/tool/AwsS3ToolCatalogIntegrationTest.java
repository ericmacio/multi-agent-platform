package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production wiring of {@link AwsS3Tool} into the catalog. No
 * {@code @ActiveProfiles("dev")} — we want the default profile so the dev-only
 * {@code TestToolFixture} is NOT in the context.
 *
 * <p>No S3 calls happen here; we only check structural registration.
 */
@SpringBootTest
class AwsS3ToolCatalogIntegrationTest {

    @Autowired private ToolCatalog toolCatalog;

    @Test
    void catalog_contains_AwsS3Tool() {
        assertThat(toolCatalog.contains("AwsS3Tool")).isTrue();

        ToolDescriptor descriptor = toolCatalog.all().stream()
                .filter(d -> d.name().equals("AwsS3Tool"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "AwsS3Tool missing from production catalog: " + toolCatalog.all()));
        assertThat(descriptor.description())
                .contains("AWS S3")
                .isNotBlank();
    }

    @Test
    void test_tool_fixture_is_absent_from_the_default_profile_context() {
        // The dev-only TestToolFixture must not leak into a production context. This
        // is the assertion that protects against an accidental drop of the
        // @Profile("dev") guard.
        assertThat(toolCatalog.contains("TestTool")).isFalse();
    }
}
