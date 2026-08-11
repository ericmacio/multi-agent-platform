package com.cognizant.emk.multiagent.domain.tool;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Catalog entry describing one tool exposed to agents (design §13, REQ-TOOL-001 / -003).
 *
 * <p>The catalog is static and small (one entry in v1: {@code AwsS3Tool}). Length /
 * blank validation is enforced here so the {@code agent_tools.tool_name varchar(64)}
 * column and the openapi {@code ToolDescriptor.name maxLength: 64} contract are
 * respected by construction.
 */
public record ToolDescriptor(String name, String description) {

    private static final int MAX_NAME_LENGTH = 64;

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new ValidationException("name", "must not be empty");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new ValidationException(
                    "name", "must be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (description == null || description.isBlank()) {
            throw new ValidationException("description", "must not be empty");
        }
    }
}
