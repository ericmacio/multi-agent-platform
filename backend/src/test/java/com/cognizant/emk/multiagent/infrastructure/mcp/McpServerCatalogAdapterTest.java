package com.cognizant.emk.multiagent.infrastructure.mcp;

import com.cognizant.emk.multiagent.application.mcp.McpServerDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Java unit test for {@link McpServerCatalogAdapter}. The adapter is exercised
 * directly via its package-private {@code scan()} method — no Spring context.
 *
 * <p>The Spring AI {@link McpStdioClientProperties} bean is fed through an
 * {@link ObjectProvider} stub so the test controls exactly which connections appear
 * in the catalog. The integration-style wiring test lives in
 * {@code McpServerCatalogAdapterIntegrationTest}.
 */
class McpServerCatalogAdapterTest {

    @Test
    void produces_sorted_descriptors_with_known_descriptions_for_brave_search_and_filesystem() {
        McpStdioClientProperties properties = newProperties(Map.of(
                "filesystem", paramsOf("npx"),
                "brave-search", paramsOf("npx")));

        McpServerCatalogAdapter adapter = new McpServerCatalogAdapter(provider(properties));
        adapter.scan();

        assertThat(adapter.all())
                .extracting(McpServerDescriptor::name)
                .containsExactly("brave-search", "filesystem");
        assertThat(adapter.all())
                .extracting(McpServerDescriptor::description)
                .containsExactly("Web search via Brave.", "Per-user local filesystem access.");
    }

    @Test
    void returns_a_null_description_for_an_unknown_connection_name() {
        McpStdioClientProperties properties = newProperties(Map.of(
                "brave-search", paramsOf("npx"),
                "filesystem", paramsOf("npx"),
                "test-mcp", paramsOf("test")));

        McpServerCatalogAdapter adapter = new McpServerCatalogAdapter(provider(properties));
        adapter.scan();

        McpServerDescriptor testMcp = adapter.all().stream()
                .filter(d -> d.name().equals("test-mcp"))
                .findFirst().orElseThrow();
        assertThat(testMcp.description()).isNull();
    }

    @Test
    void all_returns_descriptors_sorted_by_name() {
        McpStdioClientProperties properties = newProperties(Map.of(
                "zeta", paramsOf("c"),
                "alpha", paramsOf("c"),
                "mu", paramsOf("c")));

        McpServerCatalogAdapter adapter = new McpServerCatalogAdapter(provider(properties));
        adapter.scan();

        assertThat(adapter.all())
                .extracting(McpServerDescriptor::name)
                .containsExactly("alpha", "mu", "zeta");
    }

    @Test
    void contains_distinguishes_known_from_unknown_names_case_sensitively() {
        McpStdioClientProperties properties = newProperties(Map.of(
                "brave-search", paramsOf("npx")));

        McpServerCatalogAdapter adapter = new McpServerCatalogAdapter(provider(properties));
        adapter.scan();

        assertThat(adapter.contains("brave-search")).isTrue();
        assertThat(adapter.contains("Brave-Search")).isFalse();
        assertThat(adapter.contains("does-not-exist")).isFalse();
    }

    @Test
    void all_returns_an_unmodifiable_list() {
        McpStdioClientProperties properties = newProperties(Map.of(
                "brave-search", paramsOf("npx")));

        McpServerCatalogAdapter adapter = new McpServerCatalogAdapter(provider(properties));
        adapter.scan();

        List<McpServerDescriptor> snapshot = adapter.all();
        org.assertj.core.api.Assertions.assertThatThrownBy(snapshot::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void empty_connections_map_yields_an_empty_catalog() {
        McpStdioClientProperties properties = newProperties(Map.of());

        McpServerCatalogAdapter adapter = new McpServerCatalogAdapter(provider(properties));
        adapter.scan();

        assertThat(adapter.all()).isEmpty();
        assertThat(adapter.contains("brave-search")).isFalse();
    }

    @Test
    void absent_properties_bean_yields_an_empty_catalog() {
        // Models the test-profile case where Spring AI MCP autoconfig has been excluded
        // and the McpStdioClientProperties bean does not exist in the context.
        @SuppressWarnings("unchecked")
        ObjectProvider<McpStdioClientProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        McpServerCatalogAdapter adapter = new McpServerCatalogAdapter(provider);
        adapter.scan();

        assertThat(adapter.all()).isEmpty();
        assertThat(adapter.contains("brave-search")).isFalse();
    }

    private static McpStdioClientProperties.Parameters paramsOf(String command) {
        return new McpStdioClientProperties.Parameters(command, List.of(), Map.of());
    }

    private static McpStdioClientProperties newProperties(
            Map<String, McpStdioClientProperties.Parameters> connections) {
        McpStdioClientProperties properties = new McpStdioClientProperties();
        // The connections map is exposed mutably by Spring AI; populating it directly
        // is the same path the YAML binding takes.
        connections.forEach((name, params) -> properties.getConnections().put(name, params));
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpStdioClientProperties> provider(McpStdioClientProperties properties) {
        ObjectProvider<McpStdioClientProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
    }
}
