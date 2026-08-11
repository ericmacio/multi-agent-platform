package com.cognizant.emk.multiagent.infrastructure.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link McpToolCallbackResolver}. The resolver's happy path
 * (mapping enabled MCP names to real Spring AI {@code SyncMcpToolCallback}
 * instances) drives an actual {@code listTools()} call on the underlying
 * {@link McpSyncClient} — mocked here — so these tests focus on the
 * mapping / filtering / degradation contract; the end-to-end integration
 * with the OpenAI adapter is covered in
 * {@code OpenAiChatClientAdapterMcpWiringTest}.
 */
class McpToolCallbackResolverTest {

    @Test
    void empty_enabled_list_returns_empty_callbacks() {
        McpToolCallbackResolver resolver = new McpToolCallbackResolver(
                providerOf(List.of(clientNamed("brave-search"))),
                providerOf(propertiesWith("brave-search")));

        assertThat(resolver.resolve(List.of())).isEmpty();
    }

    @Test
    void null_enabled_list_returns_empty_callbacks() {
        McpToolCallbackResolver resolver = new McpToolCallbackResolver(
                providerOf(List.of(clientNamed("brave-search"))),
                providerOf(propertiesWith("brave-search")));

        assertThat(resolver.resolve(null)).isEmpty();
    }

    @Test
    void absent_client_bean_yields_no_callbacks_even_for_configured_names() {
        // Models the test-profile case where MCP autoconfig has been excluded and
        // no McpSyncClient list is present in the context.
        McpToolCallbackResolver resolver = new McpToolCallbackResolver(
                providerOf(null),
                providerOf(propertiesWith("brave-search")));

        assertThat(resolver.resolve(List.of("brave-search"))).isEmpty();
    }

    @Test
    void absent_properties_bean_yields_no_callbacks_even_when_clients_present() {
        McpSyncClient client = clientNamed("brave-search");
        McpToolCallbackResolver resolver = new McpToolCallbackResolver(
                providerOf(List.of(client)),
                providerOf(null));

        assertThat(resolver.resolve(List.of("brave-search"))).isEmpty();
        // The resolver must never call listTools() on a client that has not been
        // mapped — no MCP subprocess is contacted when the mapping is empty.
        verify(client, never()).listTools();
    }

    @Test
    void unknown_enabled_name_is_skipped_no_client_touched() {
        McpSyncClient braveClient = clientNamed("brave-search");
        McpToolCallbackResolver resolver = new McpToolCallbackResolver(
                providerOf(List.of(braveClient)),
                providerOf(propertiesWith("brave-search")));

        assertThat(resolver.resolve(List.of("filesystem"))).isEmpty();
        verify(braveClient, never()).listTools();
    }

    @Test
    void only_enabled_clients_are_asked_for_tools() {
        McpSyncClient brave = clientNamed("brave-search");
        McpSyncClient fs = clientNamed("filesystem");
        when(brave.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        when(fs.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));

        McpToolCallbackResolver resolver = new McpToolCallbackResolver(
                providerOf(List.of(brave, fs)),
                providerOf(propertiesWith("brave-search", "filesystem")));

        List<ToolCallback> callbacks = resolver.resolve(List.of("filesystem"));

        assertThat(callbacks).isEmpty();
        verify(fs).listTools();
        verify(brave, never()).listTools();
    }

    // ---- helpers ----

    private static McpSyncClient clientNamed(String connectionName) {
        McpSyncClient client = mock(McpSyncClient.class);
        McpSchema.Implementation info = implementation(connectionName);
        when(client.getClientInfo()).thenReturn(info);
        return client;
    }

    /**
     * Mirrors the shape produced by {@code McpClientAutoConfiguration} for stdio
     * sync clients: name = "&lt;commonProperties.name&gt; - &lt;connectionName&gt;",
     * title = connectionName, version = ...
     */
    private static McpSchema.Implementation implementation(String connectionName) {
        return new McpSchema.Implementation(
                "spring-ai-mcp-client - " + connectionName,
                connectionName,
                "1.0.0");
    }

    private static McpStdioClientProperties propertiesWith(String... connectionNames) {
        McpStdioClientProperties properties = new McpStdioClientProperties();
        for (String name : connectionNames) {
            properties.getConnections().put(
                    name, new McpStdioClientProperties.Parameters("npx", List.of(), Map.of()));
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
