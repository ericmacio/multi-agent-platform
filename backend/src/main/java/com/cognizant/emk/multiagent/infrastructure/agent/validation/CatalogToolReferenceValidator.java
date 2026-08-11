package com.cognizant.emk.multiagent.infrastructure.agent.validation;

import com.cognizant.emk.multiagent.application.agent.ToolReferenceValidator;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Catalog-backed implementation of {@link ToolReferenceValidator} (US-07-005,
 * REQ-TOOL-004). Replaces the EPIC-06 {@code NoopToolReferenceValidator} stub.
 *
 * <p>For every tool name supplied by the agent write path, the validator checks the
 * static {@link ToolCatalog}. The first unknown name short-circuits with
 * {@link ValidationException} carrying field {@code "tools"}. An empty input list
 * passes silently — empty {@code agent.tools} is a valid agent.
 *
 * <p>Note on layering: this component depends on {@code domain.tool.ToolCatalog}
 * (a domain port) plus the application port — no Spring Web / JPA imports, which
 * keeps it strictly an infrastructure adapter wiring two ports together.
 */
@Component
public class CatalogToolReferenceValidator implements ToolReferenceValidator {

    private final ToolCatalog toolCatalog;

    public CatalogToolReferenceValidator(ToolCatalog toolCatalog) {
        this.toolCatalog = toolCatalog;
    }

    @Override
    public void validate(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        for (String name : toolNames) {
            if (!toolCatalog.contains(name)) {
                throw new ValidationException("tools", "unknown tool: " + name);
            }
        }
    }
}
