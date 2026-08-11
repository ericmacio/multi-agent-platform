package com.cognizant.emk.multiagent.infrastructure.mcp;

import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full Spring context and asserts {@link McpServerCatalogAdapter} is wired
 * against the application's {@code McpServerCatalog} port, then verifies it produces
 * descriptors from a {@link McpStdioClientProperties} bean carrying three synthetic
 * connections (the two preconfigured production names {@code brave-search} and
 * {@code filesystem} plus a {@code test-mcp} entry whose description comes back as
 * {@code null}).
 *
 * <p>Spring AI's MCP autoconfigs are excluded across the whole test suite (see
 * {@code src/test/resources/application.yaml}) so no MCP {@code npx} subprocess is
 * ever spawned by a test context: the test asserts the catalog machinery, not the
 * runtime. A {@link TestConfig} bean supplies the {@link McpStdioClientProperties}
 * the adapter consumes — without it, the catalog would be empty because the
 * autoconfig's {@code @EnableConfigurationProperties} is also disabled.
 */
@SpringBootTest
@ActiveProfiles("dev")
class McpServerCatalogAdapterIntegrationTest {

    @Autowired private McpServerCatalog mcpServerCatalog;

    @Test
    void catalog_contains_the_three_seeded_connections_in_sorted_order() {
        assertThat(mcpServerCatalog.all())
                .extracting("name")
                .containsExactly("brave-search", "filesystem", "test-mcp");
    }

    @Test
    void brave_search_and_filesystem_carry_the_documented_descriptions() {
        Map<String, String> descriptions = mcpServerCatalog.all().stream()
                .collect(java.util.stream.Collectors.toMap(
                        d -> d.name(), d -> String.valueOf(d.description())));

        assertThat(descriptions).containsEntry("brave-search", "Web search via Brave.");
        assertThat(descriptions).containsEntry("filesystem", "Per-user local filesystem access.");
    }

    @Test
    void unknown_connection_name_yields_a_null_description() {
        var descriptor = mcpServerCatalog.all().stream()
                .filter(d -> d.name().equals("test-mcp"))
                .findFirst().orElseThrow();
        assertThat(descriptor.description()).isNull();
    }

    @Test
    void contains_distinguishes_known_from_unknown_names() {
        assertThat(mcpServerCatalog.contains("brave-search")).isTrue();
        assertThat(mcpServerCatalog.contains("filesystem")).isTrue();
        assertThat(mcpServerCatalog.contains("test-mcp")).isTrue();
        assertThat(mcpServerCatalog.contains("does-not-exist")).isFalse();
    }

    /**
     * Provides the {@link McpStdioClientProperties} the adapter consumes. Spring AI's
     * autoconfig would normally publish this bean via {@code @EnableConfigurationProperties}
     * but the autoconfig is excluded for this test (see {@link SpringBootTest#properties}),
     * so we wire the bean directly with three deterministic connections.
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        McpStdioClientProperties testMcpStdioClientProperties() {
            McpStdioClientProperties properties = new McpStdioClientProperties();
            properties.getConnections().put("brave-search", inertParameters());
            properties.getConnections().put("filesystem", inertParameters());
            properties.getConnections().put("test-mcp", inertParameters());
            return properties;
        }

        private static McpStdioClientProperties.Parameters inertParameters() {
            // command/args/env are never used: StdioTransportAutoConfiguration is excluded
            // so no subprocess gets spawned. The catalog adapter only reads the keys.
            return new McpStdioClientProperties.Parameters("noop", List.of(), Map.of());
        }
    }
}
