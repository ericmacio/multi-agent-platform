package com.cognizant.emk.multiagent.domain.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGroupAnnotationTest {

    @Test
    void annotation_round_trips_name_and_description_via_reflection() {
        ToolGroup annotation = AnnotatedFixture.class.getAnnotation(ToolGroup.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("FixtureTool");
        assertThat(annotation.description()).isEqualTo("Used by the unit test only.");
    }

    @Test
    void unannotated_class_returns_null_for_the_annotation() {
        // Sanity: the reflective check the catalog adapter relies on yields null when
        // the annotation is absent, so the adapter can filter it out.
        assertThat(UnannotatedFixture.class.getAnnotation(ToolGroup.class)).isNull();
    }

    @ToolGroup(name = "FixtureTool", description = "Used by the unit test only.")
    private static final class AnnotatedFixture {
    }

    private static final class UnannotatedFixture {
    }
}
