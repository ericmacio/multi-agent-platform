package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;

/**
 * Agent name (REQ-AGT-001 / REQ-AGT-002). Up to 32 characters, non-blank,
 * case-sensitive. Uniqueness per owner is enforced at the application layer
 * (REQ-AGT-002); this value object only encapsulates structural validation.
 *
 * <p>Unlike {@code Email}, the name is NOT lowercased — {@code "alpha"} and
 * {@code "Alpha"} are different names.
 */
public record AgentName(String value) {

    private static final int MAX_LENGTH = 32;

    public AgentName {
        if (value == null || value.isBlank()) {
            throw new ValidationException("name", "must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new ValidationException("name", "must be at most " + MAX_LENGTH + " characters");
        }
    }
}
