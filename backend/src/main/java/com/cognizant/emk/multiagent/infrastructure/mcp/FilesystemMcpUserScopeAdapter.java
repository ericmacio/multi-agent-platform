package com.cognizant.emk.multiagent.infrastructure.mcp;

import com.cognizant.emk.multiagent.application.mcp.FilesystemMcpUserScope;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import com.cognizant.emk.multiagent.infrastructure.error.McpServerException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Per-user filesystem scope adapter for the {@code filesystem} MCP server
 * (design §14, REQ-MCP-005).
 *
 * <p>Reads the base path from {@code app.mcp.filesystem.base} once at
 * construction and normalizes it to an absolute path. Each call to
 * {@link #resolveUserRoot(UserId)} computes {@code <base>/users/<userId>},
 * asserts containment under the base (defense-in-depth even though
 * {@link UserId} wraps a UUID), creates the directory tree on demand
 * ({@link Files#createDirectories(Path, java.nio.file.attribute.FileAttribute[])}
 * is idempotent), and returns the path.
 *
 * <p>Filesystem failures are wrapped in {@link McpServerException} (US-08-007),
 * which the REST boundary maps to HTTP 502 {@code MCP_SERVER_ERROR}. Error
 * messages MUST NOT contain user-controlled path fragments (REQ-SEC-004).
 *
 * <p>The adapter holds no mutable state beyond the immutable normalized base,
 * so it is safe to share across threads.
 */
@Component
public class FilesystemMcpUserScopeAdapter implements FilesystemMcpUserScope {

    private final Path base;

    public FilesystemMcpUserScopeAdapter(ApplicationProperties properties) {
        this.base = Path.of(properties.mcp().filesystem().base()).toAbsolutePath().normalize();
    }

    @Override
    public Path resolveUserRoot(UserId userId) {
        Path target = base.resolve("users").resolve(userId.value().toString()).normalize();

        // Defense-in-depth: UserId wraps a UUID so traversal can't actually be triggered,
        // but the check keeps the security property local to this adapter and surfaces a
        // single, sanitized error if the invariant is ever broken upstream. The offending
        // path is NOT logged (REQ-SEC-004).
        if (!target.startsWith(base)) {
            throw new McpServerException("resolved per-user MCP root escapes the configured base");
        }

        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new McpServerException("failed to create per-user MCP filesystem root", e);
        }

        return target;
    }
}
