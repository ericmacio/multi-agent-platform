package com.cognizant.emk.multiagent.infrastructure.agent.validation;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import com.cognizant.emk.multiagent.domain.tool.ToolCatalog;
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
class CatalogToolReferenceValidatorTest {

    @Mock private ToolCatalog toolCatalog;
    @InjectMocks private CatalogToolReferenceValidator validator;

    @Test
    void empty_list_short_circuits_without_consulting_the_catalog() {
        validator.validate(List.of());
        verify(toolCatalog, never()).contains(anyString());
    }

    @Test
    void null_list_is_treated_as_empty_and_does_not_throw() {
        validator.validate(null);
        verify(toolCatalog, never()).contains(anyString());
    }

    @Test
    void every_name_in_the_catalog_passes_silently() {
        when(toolCatalog.contains("AwsS3Tool")).thenReturn(true);
        when(toolCatalog.contains("OtherTool")).thenReturn(true);

        validator.validate(List.of("AwsS3Tool", "OtherTool"));

        verify(toolCatalog, times(1)).contains("AwsS3Tool");
        verify(toolCatalog, times(1)).contains("OtherTool");
    }

    @Test
    void first_unknown_name_short_circuits_and_carries_the_name_in_the_message() {
        when(toolCatalog.contains("known")).thenReturn(true);
        when(toolCatalog.contains("unknown-one")).thenReturn(false);

        assertThatThrownBy(() -> validator.validate(
                List.of("known", "unknown-one", "would-be-second")))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("tools");
                    assertThat(ex.getMessage()).contains("unknown-one");
                });

        // Short-circuit: the third name MUST NOT be inspected.
        verify(toolCatalog, never()).contains("would-be-second");
    }
}
