package com.cognizant.emk.multiagent.infrastructure.web.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;

/**
 * Pure-static mapper from the {@link ToolDescriptor} domain record to the
 * {@link ToolDescriptorResponse} wire shape.
 */
public final class ToolResponseMapper {

    private ToolResponseMapper() {}

    public static ToolDescriptorResponse toResponse(ToolDescriptor descriptor) {
        return new ToolDescriptorResponse(descriptor.name(), descriptor.description());
    }
}
