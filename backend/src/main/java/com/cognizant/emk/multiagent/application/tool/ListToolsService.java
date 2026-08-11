package com.cognizant.emk.multiagent.application.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ListToolsUseCase} implementation — pure forwarder to
 * {@link ToolCatalog#all()}.
 *
 * <p>{@code @Transactional(readOnly = true)} is for symmetry with sibling read use
 * cases; the catalog access has no DB hit.
 */
@Service
public class ListToolsService implements ListToolsUseCase {

    private final ToolCatalog toolCatalog;

    public ListToolsService(ToolCatalog toolCatalog) {
        this.toolCatalog = toolCatalog;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ToolDescriptor> list() {
        return toolCatalog.all();
    }
}
