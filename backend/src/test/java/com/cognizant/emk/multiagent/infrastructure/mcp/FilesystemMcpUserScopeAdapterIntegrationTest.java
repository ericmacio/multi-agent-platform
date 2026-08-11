package com.cognizant.emk.multiagent.infrastructure.mcp;

import com.cognizant.emk.multiagent.application.mcp.FilesystemMcpUserScope;
import com.cognizant.emk.multiagent.domain.user.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full Spring context and asserts {@link FilesystemMcpUserScopeAdapter}
 * is wired against the application's {@link FilesystemMcpUserScope} port and
 * resolves a per-user root rooted at the configured {@code app.mcp.filesystem.base}.
 *
 * <p>{@code MCP_FS_BASE} is overridden to a JUnit {@link TempDir} via
 * {@link DynamicPropertySource} so the test never writes outside the temp
 * directory.
 */
@SpringBootTest
class FilesystemMcpUserScopeAdapterIntegrationTest {

    @TempDir static Path mcpBase;

    @DynamicPropertySource
    static void overrideMcpBase(DynamicPropertyRegistry registry) {
        registry.add("app.mcp.filesystem.base", () -> mcpBase.toString());
    }

    @Autowired private FilesystemMcpUserScope filesystemMcpUserScope;

    @Test
    void resolves_a_per_user_folder_rooted_at_the_configured_base() {
        UserId userId = UserId.of(UUID.randomUUID());

        Path root = filesystemMcpUserScope.resolveUserRoot(userId);

        assertThat(root).isDirectory();
        assertThat(root.startsWith(mcpBase.toAbsolutePath().normalize())).isTrue();
        assertThat(root.getFileName().toString()).isEqualTo(userId.value().toString());
    }

    @Test
    void resolved_path_is_absolute(@TempDir Path ignored) {
        Path root = filesystemMcpUserScope.resolveUserRoot(UserId.of(UUID.randomUUID()));
        assertThat(root.isAbsolute()).isTrue();
    }

    @Test
    void resolved_folder_is_empty_after_creation() throws Exception {
        Path root = filesystemMcpUserScope.resolveUserRoot(UserId.of(UUID.randomUUID()));

        try (var stream = Files.list(root)) {
            assertThat(stream.count()).isZero();
        }
    }
}
