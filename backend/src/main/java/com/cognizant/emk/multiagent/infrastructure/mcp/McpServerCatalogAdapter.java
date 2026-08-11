package com.cognizant.emk.multiagent.infrastructure.mcp;

import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import com.cognizant.emk.multiagent.application.mcp.McpServerDescriptor;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring-backed implementation of {@link McpServerCatalog} (design §14, REQ-MCP-001 /
 * -006).
 *
 * <p>At startup ({@link PostConstruct}) the adapter reads Spring AI's
 * {@link McpStdioClientProperties#getConnections()} map and produces one
 * {@link McpServerDescriptor} per declared stdio MCP connection. Descriptions come
 * from the small internal lookup {@link #KNOWN_DESCRIPTIONS} keyed on the connection
 * name; unknown names return a {@code null} description (matching the openapi
 * {@code McpServerDescriptor.description: nullable: true} contract). The result is
 * sorted by name and cached for the lifetime of the JVM — the catalog is static
 * per REQ-MCP-001.
 *
 * <p>The {@link McpStdioClientProperties} bean is injected through an
 * {@link ObjectProvider} so the adapter works in tests where the MCP autoconfig has
 * been excluded: an absent bean simply yields an empty catalog. This keeps the
 * adapter Spring-friendly without forcing every test context to also wire the
 * Spring AI MCP autoconfig.
 */
@Component
public class McpServerCatalogAdapter implements McpServerCatalog {

    private static final Logger log = LoggerFactory.getLogger(McpServerCatalogAdapter.class);

    /**
     * Spring AI's MCP stdio configuration carries no description field, so descriptions
     * live in code keyed on the connection name. Adding a new preconfigured MCP server
     * means adding both an {@code application.yaml} connection block and an entry here.
     */
    private static final Map<String, String> KNOWN_DESCRIPTIONS = Map.of(
            "brave-search", "Web search via Brave.",
            "filesystem", "Per-user local filesystem access.");

    private final ObjectProvider<McpStdioClientProperties> propertiesProvider;

    private List<McpServerDescriptor> snapshot = List.of();
    private Map<String, McpServerDescriptor> byName = Map.of();

    public McpServerCatalogAdapter(ObjectProvider<McpStdioClientProperties> propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    @PostConstruct
    void scan() {
        McpStdioClientProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null) {
            log.info("MCP catalog populated with 0 entries (Spring AI MCP autoconfig not loaded)");
            return;
        }

        Map<String, McpStdioClientProperties.Parameters> connections = properties.getConnections();
        Map<String, McpServerDescriptor> collected = new LinkedHashMap<>();

        for (String name : connections.keySet()) {
            String description = KNOWN_DESCRIPTIONS.get(name);
            // Constructing the descriptor enforces the structural rules (≤ 64 chars
            // name, non-blank) via ValidationException at startup.
            McpServerDescriptor descriptor = new McpServerDescriptor(name, description);
            if (collected.put(descriptor.name(), descriptor) != null) {
                // Spring AI's connections map is keyed by String, so this branch is
                // structurally unreachable from yaml. It guards against a future
                // alternative loader (e.g. servers-configuration JSON) that might
                // merge two sources with the same key.
                throw new IllegalStateException(
                        "Duplicate MCP server catalog entry '" + descriptor.name() + "'");
            }
        }

        this.snapshot = collected.values().stream()
                .sorted(Comparator.comparing(McpServerDescriptor::name))
                .toList();
        this.byName = Map.copyOf(collected);
        log.info("MCP catalog populated with {} entries: {}",
                snapshot.size(), snapshot.stream().map(McpServerDescriptor::name).toList());
    }

    @Override
    public List<McpServerDescriptor> all() {
        return snapshot;
    }

    @Override
    public boolean contains(String name) {
        return byName.containsKey(name);
    }
}
