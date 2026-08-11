package com.cognizant.emk.multiagent.infrastructure.web.tool;

/**
 * Wire shape for one tool entry. Matches the openapi {@code ToolDescriptor} schema.
 */
public record ToolDescriptorResponse(String name, String description) {
}
