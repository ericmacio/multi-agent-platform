package com.cognizant.emk.multiagent.infrastructure.web.tool;

import com.cognizant.emk.multiagent.application.tool.ListToolsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the tools catalog (REQ-TOOL-003, design §6.2.5).
 *
 * <p>Open to any authenticated principal — STANDARD JWT, ADMIN JWT, and SYSTEM
 * API-key all admitted per design §8.6. The existing {@code apiPattern.authenticated()}
 * catch-all rule in {@code SpringSecurityConfig} handles authorization; no new
 * URL guard is needed.
 *
 * <p>No class-level {@code @RequestMapping}: the {@code /api/v1} prefix is applied
 * centrally by {@code WebConfig}.
 */
@RestController
public class ToolsController {

    private final ListToolsUseCase listToolsUseCase;

    public ToolsController(ListToolsUseCase listToolsUseCase) {
        this.listToolsUseCase = listToolsUseCase;
    }

    @GetMapping("/tools")
    public ToolListResponse list() {
        return new ToolListResponse(
                listToolsUseCase.list().stream()
                        .map(ToolResponseMapper::toResponse)
                        .toList());
    }
}
