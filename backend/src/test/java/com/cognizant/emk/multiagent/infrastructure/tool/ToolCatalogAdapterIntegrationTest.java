package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for {@link ToolCatalogAdapter} on the dev profile. The
 * catalog should contain at least:
 * <ul>
 *   <li>{@code AwsS3Tool} — production bean from US-07-003;</li>
 *   <li>{@code TestTool} — dev-profile fixture in {@link TestToolFixture}.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("dev")
class ToolCatalogAdapterIntegrationTest {

    @Autowired private ToolCatalog toolCatalog;

    @Test
    void catalog_contains_AwsS3Tool_and_the_test_fixture() {
        assertThat(toolCatalog.all())
                .extracting("name")
                .contains("AwsS3Tool", "TestTool");
    }

    @Test
    void contains_returns_true_for_a_known_name_and_false_for_an_unknown_one() {
        assertThat(toolCatalog.contains("AwsS3Tool")).isTrue();
        assertThat(toolCatalog.contains("TestTool")).isTrue();
        assertThat(toolCatalog.contains("does-not-exist")).isFalse();
    }

    @Test
    void all_returns_descriptors_sorted_by_name() {
        var names = toolCatalog.all().stream().map(d -> d.name()).toList();
        var expected = names.stream().sorted().toList();
        assertThat(names).isEqualTo(expected);
    }

    @Test
    void test_tool_fixture_carries_the_documented_description() {
        var descriptor = toolCatalog.all().stream()
                .filter(d -> d.name().equals("TestTool"))
                .findFirst().orElseThrow();
        assertThat(descriptor.description()).isEqualTo("Used by the unit test only.");
    }
}
