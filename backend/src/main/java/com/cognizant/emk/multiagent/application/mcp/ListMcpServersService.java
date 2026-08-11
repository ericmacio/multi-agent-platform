package com.cognizant.emk.multiagent.application.mcp;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ListMcpServersUseCase} implementation — pure forwarder to
 * {@link McpServerCatalog#all()}.
 *
 * <p>{@code @Transactional(readOnly = true)} is for symmetry with sibling read use
 * cases; the catalog access has no DB hit.
 */
@Service
public class ListMcpServersService implements ListMcpServersUseCase {

    private final McpServerCatalog mcpServerCatalog;

    public ListMcpServersService(McpServerCatalog mcpServerCatalog) {
        this.mcpServerCatalog = mcpServerCatalog;
    }

    @Override
    @Transactional(readOnly = true)
    public List<McpServerDescriptor> list() {
        return mcpServerCatalog.all();
    }
}
