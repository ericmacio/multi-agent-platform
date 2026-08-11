package com.cognizant.emk.multiagent.infrastructure.mcp;

import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties.Mcp;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties.Mcp.Filesystem;
import com.cognizant.emk.multiagent.infrastructure.error.McpServerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-Java unit test for {@link FilesystemMcpUserScopeAdapter}. Uses
 * {@link TempDir} for the MCP filesystem base so the test never touches a real
 * configured path and never spawns a subprocess.
 */
class FilesystemMcpUserScopeAdapterTest {

    @Test
    void first_call_for_a_user_creates_the_per_user_root(@TempDir Path tmp) {
        FilesystemMcpUserScopeAdapter adapter = newAdapter(tmp);
        UserId userId = UserId.of(UUID.randomUUID());

        Path root = adapter.resolveUserRoot(userId);

        assertThat(root).isDirectory();
        assertThat(root).isEqualTo(tmp.toAbsolutePath().normalize()
                .resolve("users").resolve(userId.value().toString()));
    }

    @Test
    void second_call_for_the_same_user_is_idempotent(@TempDir Path tmp) throws IOException {
        FilesystemMcpUserScopeAdapter adapter = newAdapter(tmp);
        UserId userId = UserId.of(UUID.randomUUID());

        Path first = adapter.resolveUserRoot(userId);
        // Drop a marker file so the test can prove the second call DID NOT recreate
        // (which would wipe contents). createDirectories is idempotent on existing
        // directories — we want to assert nothing gets clobbered.
        Path marker = first.resolve("marker.txt");
        Files.writeString(marker, "hello");

        Path second = adapter.resolveUserRoot(userId);

        assertThat(second).isEqualTo(first);
        assertThat(marker).exists();
        assertThat(Files.readString(marker)).isEqualTo("hello");
    }

    @Test
    void roots_for_different_users_do_not_overlap(@TempDir Path tmp) {
        FilesystemMcpUserScopeAdapter adapter = newAdapter(tmp);

        Path u1 = adapter.resolveUserRoot(UserId.of(UUID.randomUUID()));
        Path u2 = adapter.resolveUserRoot(UserId.of(UUID.randomUUID()));

        assertThat(u1).isNotEqualTo(u2);
        assertThat(u1.getParent()).isEqualTo(u2.getParent());
        assertThat(u1.getParent().getFileName().toString()).isEqualTo("users");
    }

    @Test
    void returned_path_is_absolute(@TempDir Path tmp) {
        // EPIC-11 will hand this path to `npx ... server-filesystem`; relative segments
        // would resolve against an unpredictable working directory.
        FilesystemMcpUserScopeAdapter adapter = newAdapter(tmp);

        Path root = adapter.resolveUserRoot(UserId.of(UUID.randomUUID()));

        assertThat(root.isAbsolute()).isTrue();
    }

    @Test
    void io_failure_is_wrapped_in_McpServerException_without_leaking_the_user_id(
            @TempDir Path tmp) throws IOException {
        // Force the IOException branch by making `<base>/users` a regular file: the
        // per-user directory creation now collides with a non-directory parent and
        // Files.createDirectories throws.
        Path usersPath = tmp.resolve("users");
        Files.writeString(usersPath, "intentionally a file, not a directory");

        FilesystemMcpUserScopeAdapter adapter = newAdapter(tmp);
        UserId userId = UserId.of(UUID.randomUUID());

        assertThatThrownBy(() -> adapter.resolveUserRoot(userId))
                .isInstanceOfSatisfying(McpServerException.class, ex -> {
                    assertThat(ex.getCause()).isInstanceOf(IOException.class);
                    // User-controlled value MUST NOT appear in the message (REQ-SEC-004).
                    assertThat(ex.getMessage()).doesNotContain(userId.value().toString());
                });
    }

    private static FilesystemMcpUserScopeAdapter newAdapter(Path base) {
        ApplicationProperties properties = new ApplicationProperties(
                null, null, null, null,
                new Mcp(new Filesystem(base.toString())),
                null, null);
        return new FilesystemMcpUserScopeAdapter(properties);
    }
}
