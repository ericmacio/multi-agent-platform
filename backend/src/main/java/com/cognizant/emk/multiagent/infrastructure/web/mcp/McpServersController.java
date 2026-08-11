package com.cognizant.emk.multiagent.infrastructure.web.mcp;

import com.cognizant.emk.multiagent.application.mcp.ListMcpServersUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the MCP servers catalog (REQ-MCP-006, design §6.2.6).
 *
 * <p>Open to any authenticated principal — STANDARD JWT, ADMIN JWT, and SYSTEM
 * API-key all admitted per design §8.6. The existing
 * {@code apiPattern.authenticated()} catch-all rule in
 * {@code SpringSecurityConfig} handles authorization; no new URL guard is
 * needed.
 *
 * <p>No class-level {@code @RequestMapping}: the {@code /api/v1} prefix is
 * applied centrally by {@code WebConfig}.
 */
@RestController
public class McpServersController {

    private final ListMcpServersUseCase listMcpServersUseCase;

    public McpServersController(ListMcpServersUseCase listMcpServersUseCase) {
        this.listMcpServersUseCase = listMcpServersUseCase;
    }

    @GetMapping("/mcp-servers")
    public McpServerListResponse list() {
        return new McpServerListResponse(
                listMcpServersUseCase.list().stream()
                        .map(McpServerResponseMapper::toResponse)
                        .toList());
    }
}
