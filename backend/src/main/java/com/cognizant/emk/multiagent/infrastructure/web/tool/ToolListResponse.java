package com.cognizant.emk.multiagent.infrastructure.web.tool;

import java.util.List;

/**
 * Wire envelope for {@code GET /tools}. Matches the openapi {@code ToolList} schema.
 * No pagination — the catalog is small and static (REQ-TOOL-001, design §6.2.5).
 */
public record ToolListResponse(List<ToolDescriptorResponse> items) {
}
