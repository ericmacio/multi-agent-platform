package com.cognizant.emk.multiagent.infrastructure.agent.validation;

import com.cognizant.emk.multiagent.application.mcp.McpServerCatalog;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogMcpReferenceValidatorTest {

    @Mock private McpServerCatalog mcpServerCatalog;
    @InjectMocks private CatalogMcpReferenceValidator validator;

    @Test
    void empty_list_short_circuits_without_consulting_the_catalog() {
        validator.validate(List.of());
        verify(mcpServerCatalog, never()).contains(anyString());
    }

    @Test
    void null_list_is_treated_as_empty_and_does_not_throw() {
        validator.validate(null);
        verify(mcpServerCatalog, never()).contains(anyString());
    }

    @Test
    void every_name_in_the_catalog_passes_silently() {
        when(mcpServerCatalog.contains("brave-search")).thenReturn(true);
        when(mcpServerCatalog.contains("filesystem")).thenReturn(true);

        validator.validate(List.of("brave-search", "filesystem"));

        verify(mcpServerCatalog, times(1)).contains("brave-search");
        verify(mcpServerCatalog, times(1)).contains("filesystem");
    }

    @Test
    void first_unknown_name_short_circuits_and_carries_the_name_in_the_message() {
        when(mcpServerCatalog.contains("brave-search")).thenReturn(true);
        when(mcpServerCatalog.contains("does-not-exist")).thenReturn(false);

        assertThatThrownBy(() -> validator.validate(
                List.of("brave-search", "does-not-exist", "would-be-second")))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("enabledMcpServers");
                    assertThat(ex.getMessage()).contains("does-not-exist");
                });

        verify(mcpServerCatalog, never()).contains("would-be-second");
    }
}
