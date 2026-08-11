package com.cognizant.emk.multiagent.application.mcp;

import com.cognizant.emk.multiagent.domain.user.UserId;
import java.nio.file.Path;

/**
 * Resolves the per-user root directory for the {@code filesystem} MCP server
 * (design §14, REQ-MCP-005).
 *
 * <p>The contract is "first call creates the folder on demand": implementations
 * MUST create the directory tree if it does not yet exist and MUST guarantee
 * that the returned path stays under the configured base. They MUST NOT eagerly
 * create per-user folders at user creation time — only at first use.
 *
 * <p>On any filesystem failure, implementations throw {@code McpServerException}
 * (mapped to HTTP 502 {@code MCP_SERVER_ERROR} at the REST boundary,
 * design §9 / §14).
 *
 * <p>The Spring-AI-side wiring that hands the resolved path to a
 * {@code filesystem} MCP runtime is deferred to EPIC-11 — see TBD-2 in
 * {@code SW-DESIGN.md}. This port ships the primitive EPIC-11 will plug
 * whichever variant (per-user MCP process / shared process with path
 * rewriting) into.
 */
public interface FilesystemMcpUserScope {

    Path resolveUserRoot(UserId userId);
}
