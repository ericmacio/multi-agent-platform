package com.cognizant.emk.multiagent.application.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListMcpServersServiceTest {

    @Mock private McpServerCatalog mcpServerCatalog;
    @InjectMocks private ListMcpServersService service;

    @Test
    void forwards_the_catalog_snapshot_verbatim() {
        List<McpServerDescriptor> snapshot = List.of(
                new McpServerDescriptor("brave-search", "Web search via Brave."),
                new McpServerDescriptor("filesystem", "Per-user local filesystem access."));
        when(mcpServerCatalog.all()).thenReturn(snapshot);

        List<McpServerDescriptor> result = service.list();

        assertThat(result).isSameAs(snapshot);
    }
}
