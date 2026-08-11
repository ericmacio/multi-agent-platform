package com.cognizant.emk.multiagent.infrastructure.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link ToolCallback}s an agent's {@code enabledMcpServers} list
 * unlocks, closing the last-mile gap in the OpenAI adapter's MCP wiring
 * (REQ-AGT-009, REQ-MCP-004).
 *
 * <p>Spring AI's MCP autoconfig publishes a {@code List<McpSyncClient>} bean —
 * one client per named stdio connection in
 * {@code spring.ai.mcp.client.stdio.connections.*}. The client's client-info
 * name is built by the autoconfig as {@code "<clientName> - <connectionName>"}
 * (see {@code McpClientAutoConfiguration.connectedClientName}); we correlate
 * back to the yaml connection name by matching each client's suffix against
 * the keys of {@link McpStdioClientProperties#getConnections()} snapshotted at
 * startup.
 *
 * <p>Once the mapping is built, {@link #resolve(List)} returns exactly the tool
 * callbacks contributed by the requested subset — leveraging
 * {@link SyncMcpToolCallbackProvider} so tool-name uniqueness, cache
 * invalidation on {@code McpToolsChangedEvent}, and prefix generation stay
 * consistent with Spring AI's built-in behavior.
 *
 * <p>The provider dependencies are injected via {@link ObjectProvider} so
 * every test context that excludes the MCP autoconfig still boots — an absent
 * client bean or an absent properties bean simply yields an empty catalog and
 * every {@link #resolve(List)} call returns {@link Collections#emptyList()}.
 */
@Component
public class McpToolCallbackResolver {

    private static final Logger log = LoggerFactory.getLogger(McpToolCallbackResolver.class);

    private final Map<String, McpSyncClient> clientsByConnectionName;

    public McpToolCallbackResolver(
            ObjectProvider<List<McpSyncClient>> mcpSyncClientsProvider,
            ObjectProvider<McpStdioClientProperties> propertiesProvider) {
        this.clientsByConnectionName = buildMapping(
                mcpSyncClientsProvider.getIfAvailable(),
                propertiesProvider.getIfAvailable());
    }

    /**
     * Test-only factory that returns an instance which resolves every request
     * to an empty callback list — the intent-preserving substitute for the
     * previous "MCPs silently ignored" behavior in tests that do not exercise
     * MCP wiring. Built through the single public constructor with stub
     * {@link ObjectProvider}s so that Spring's constructor-inference stays
     * unambiguous on the production bean (Spring Boot 4.x's
     * {@code SimpleInstantiationStrategy} falls back to a no-arg constructor
     * when a class has multiple declared constructors, so a second private
     * constructor here would cause a startup {@code BeanInstantiationException}).
     */
    public static McpToolCallbackResolver noop() {
        return new McpToolCallbackResolver(emptyProvider(), emptyProvider());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) EMPTY_OBJECT_PROVIDER;
    }

    private static final ObjectProvider<Object> EMPTY_OBJECT_PROVIDER = new ObjectProvider<>() {
        @Override public Object getObject() { throw new UnsupportedOperationException(); }
        @Override public Object getObject(Object... args) { throw new UnsupportedOperationException(); }
        @Override public Object getIfAvailable() { return null; }
        @Override public Object getIfUnique() { return null; }
    };

    /**
     * Resolve the tool callbacks contributed by the given enabled MCP servers.
     *
     * <p>Unknown names are skipped with a warning — the agent-write-time
     * validator (US-08-006) already rejects unknown MCP names, so a live
     * unknown reference means the catalog changed after the agent was saved,
     * or the autoconfig failed to spawn the client at startup. In either case,
     * degrading gracefully is safer than aborting the turn.
     */
    public List<ToolCallback> resolve(List<String> enabledMcpServers) {
        if (enabledMcpServers == null || enabledMcpServers.isEmpty() || clientsByConnectionName.isEmpty()) {
            return List.of();
        }

        List<McpSyncClient> selected = new ArrayList<>(enabledMcpServers.size());
        for (String name : enabledMcpServers) {
            McpSyncClient client = clientsByConnectionName.get(name);
            if (client == null) {
                log.warn(
                        "agent references enabled MCP server '{}' but no MCP sync client "
                                + "is available under that connection name — skipping",
                        name);
                continue;
            }
            selected.add(client);
        }

        if (selected.isEmpty()) {
            return List.of();
        }

        ToolCallback[] callbacks = SyncMcpToolCallbackProvider.builder()
                .mcpClients(selected)
                .build()
                .getToolCallbacks();
        return List.of(callbacks);
    }

    private static Map<String, McpSyncClient> buildMapping(
            List<McpSyncClient> clients, McpStdioClientProperties properties) {
        if (clients == null || clients.isEmpty()) {
            log.info("MCP tool-callback resolver: no MCP sync clients on the classpath");
            return Map.of();
        }
        if (properties == null || properties.getConnections() == null || properties.getConnections().isEmpty()) {
            log.info("MCP tool-callback resolver: no stdio MCP client properties available");
            return Map.of();
        }

        // Preserve yaml insertion order so any downstream iteration (currently
        // none, but callers may add it) remains deterministic.
        Map<String, McpSyncClient> byName = new LinkedHashMap<>();
        for (String connectionName : properties.getConnections().keySet()) {
            McpSyncClient match = findClientByConnectionName(clients, connectionName);
            if (match != null) {
                byName.put(connectionName, match);
            } else {
                log.warn(
                        "MCP tool-callback resolver: no McpSyncClient bean matched "
                                + "connection '{}' — check spring.ai.mcp.client.stdio.connections",
                        connectionName);
            }
        }
        log.info("MCP tool-callback resolver: mapped {} connection(s) to sync clients: {}",
                byName.size(), byName.keySet());
        return Map.copyOf(byName);
    }

    private static McpSyncClient findClientByConnectionName(
            List<McpSyncClient> clients, String connectionName) {
        String suffix = " - " + connectionName;
        for (McpSyncClient client : clients) {
            String clientInfoName = client.getClientInfo() != null
                    ? client.getClientInfo().name()
                    : null;
            if (clientInfoName == null) {
                continue;
            }
            // Autoconfig sets clientInfo.name to "<commonProperties.name> - <connectionName>".
            // Matching by suffix keeps the resolver decoupled from the exact prefix (which
            // may change across Spring AI releases) while still pinning to the connection.
            if (clientInfoName.endsWith(suffix) || clientInfoName.equals(connectionName)) {
                return client;
            }
        }
        return null;
    }
}
