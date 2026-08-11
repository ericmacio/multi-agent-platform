package com.cognizant.emk.multiagent.application.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolDescriptor;
import java.util.List;

/**
 * Use case for {@code GET /tools} (REQ-TOOL-003).
 *
 * <p>No command record — the surface is parameterless. The catalog is static, so the
 * use case is a pure forwarder over the {@code ToolCatalog} port.
 */
public interface ListToolsUseCase {

    List<ToolDescriptor> list();
}
