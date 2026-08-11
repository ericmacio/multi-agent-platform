package com.cognizant.emk.multiagent.infrastructure.tool;

import com.cognizant.emk.multiagent.domain.tool.ToolGroup;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that two beans declaring the same {@code @ToolGroup(name = ...)} value fail
 * the application at startup with a diagnostic naming both bean classes — no silent
 * override, no chat-time surprise.
 *
 * <p>Uses a plain {@link AnnotationConfigApplicationContext} rather than
 * {@code @SpringBootTest} because the duplicate is intentional and would fail every
 * other Spring Boot test if registered globally.
 */
class ToolCatalogAdapterDuplicateNameTest {

    @Test
    void two_beans_with_the_same_tool_group_name_fail_context_refresh() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext()) {
            ctx.register(FirstDuplicate.class, SecondDuplicate.class, ToolCatalogAdapter.class);
            assertThatThrownBy(ctx::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Duplicate tool catalog entry 'Duplicate'")
                    .satisfies(ex -> {
                        // Both class names should be in the message — order is
                        // implementation-defined (HashMap iteration).
                        String msg = ex.getMessage();
                        assertThat(msg).contains(FirstDuplicate.class.getName());
                        assertThat(msg).contains(SecondDuplicate.class.getName());
                    });
        }
    }

    @Component
    @ToolGroup(name = "Duplicate", description = "first")
    static class FirstDuplicate {
    }

    @Component
    @ToolGroup(name = "Duplicate", description = "second")
    static class SecondDuplicate {
    }
}
