package com.cognizant.emk.multiagent.infrastructure.web.mcp;

import com.cognizant.emk.multiagent.application.mcp.McpServerDescriptor;

/**
 * Pure-static mapper from the {@link McpServerDescriptor} application record to
 * the {@link McpServerDescriptorResponse} wire shape.
 */
public final class McpServerResponseMapper {

    private McpServerResponseMapper() {}

    public static McpServerDescriptorResponse toResponse(McpServerDescriptor descriptor) {
        return new McpServerDescriptorResponse(descriptor.name(), descriptor.description());
    }
}
