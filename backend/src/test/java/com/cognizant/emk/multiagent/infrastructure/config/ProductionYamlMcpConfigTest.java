package com.cognizant.emk.multiagent.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test on the <em>production</em> {@code application.yaml} — verifies the MCP
 * connection block declared by US-08-002.
 *
 * <p>The test-profile {@code application.yaml} (under {@code src/test/resources}) does
 * NOT carry the {@code spring.ai.mcp.client.stdio.connections.*} block — the test
 * profile excludes {@code McpClientAutoConfiguration} so test contexts never spawn
 * {@code npx} subprocesses. This test loads the production yaml as a plain file from
 * the main classpath and asserts the two preconfigured MCP servers are declared with
 * the contract expected by REQ-MCP-002 / -003 and design §14.
 */
class ProductionYamlMcpConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void brave_search_and_filesystem_connections_are_declared_with_the_documented_contract() {
        Map<String, Object> yaml = loadProductionYaml();
        Map<String, Object> connections = navigate(yaml,
                "spring", "ai", "mcp", "client", "stdio", "connections");

        assertThat(connections).containsOnlyKeys("brave-search", "filesystem");

        Map<String, Object> brave = (Map<String, Object>) connections.get("brave-search");
        assertThat(brave.get("command")).isEqualTo("npx");
        assertThat((List<String>) brave.get("args"))
                .containsExactly("-y", "@modelcontextprotocol/server-brave-search");
        // BRAVE_API_KEY MUST come from the env var, never hardcoded (REQ-SEC-003 / REQ-MCP-003).
        Map<String, String> braveEnv = (Map<String, String>) brave.get("env");
        assertThat(braveEnv).containsEntry("BRAVE_API_KEY", "${BRAVE_API_KEY}");

        Map<String, Object> fs = (Map<String, Object>) connections.get("filesystem");
        assertThat(fs.get("command")).isEqualTo("npx");
        // The path arg references the configured base; per-user scoping happens at
        // runtime via FilesystemMcpUserScopeAdapter (US-08-004) — design §14 / TBD-2.
        assertThat((List<String>) fs.get("args"))
                .containsExactly(
                        "-y",
                        "@modelcontextprotocol/server-filesystem",
                        "${app.mcp.filesystem.base}");
    }

    @Test
    void app_mcp_filesystem_base_uses_the_relative_default_with_env_var_override() {
        Map<String, Object> yaml = loadProductionYaml();
        Map<String, Object> filesystem = navigate(yaml, "app", "mcp", "filesystem");

        // Spring placeholder syntax: env var override with a relative default that
        // writes successfully on a stock Windows laptop (no admin rights required).
        assertThat(filesystem.get("base")).isEqualTo("${MCP_FS_BASE:./var/lib/multi-agent/fs}");
    }

    private static Map<String, Object> loadProductionYaml() {
        try (InputStream stream = ProductionYamlMcpConfigTest.class
                .getResourceAsStream("/__main__/application.yaml")) {
            // Fall through to the standard classpath load below — Spring Boot publishes
            // the main application.yaml at the standard root, while the test variant
            // shadows it at the same path. We need the main variant: read it directly
            // from the filesystem under src/main/resources to bypass the shadowing.
            if (stream != null) {
                return new Yaml().load(stream);
            }
        } catch (Exception ignored) {
            // fall through
        }
        // Direct filesystem load — survives shadowing by the test classpath.
        java.nio.file.Path mainYaml = java.nio.file.Path.of(
                "src", "main", "resources", "application.yaml");
        try (InputStream stream = java.nio.file.Files.newInputStream(mainYaml)) {
            return new Yaml().load(stream);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "Production application.yaml not found at " + mainYaml.toAbsolutePath(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> navigate(Map<String, Object> root, String... path) {
        Map<String, Object> node = root;
        for (String key : path) {
            Object next = node.get(key);
            assertThat(next)
                    .as("yaml path %s/%s", String.join("/", path), key)
                    .isInstanceOf(Map.class);
            node = (Map<String, Object>) next;
        }
        return node;
    }
}
