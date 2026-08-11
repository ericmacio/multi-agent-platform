package com.cognizant.emk.multiagent.application.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.List;

/**
 * Validates that every entry in {@code toolNames} refers to a known tool in the
 * static catalog (REQ-TOOL-004).
 *
 * <p>EPIC-06 ships {@code NoopToolReferenceValidator} which accepts everything;
 * EPIC-07 replaces it with a catalog-backed implementation. The contract here is:
 * throw {@link ValidationException} with field {@code "tools"} on the first
 * unknown name.
 */
public interface ToolReferenceValidator {

    void validate(List<String> toolNames);
}
