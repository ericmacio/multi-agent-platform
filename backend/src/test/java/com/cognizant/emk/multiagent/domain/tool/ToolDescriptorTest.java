package com.cognizant.emk.multiagent.domain.tool;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDescriptorTest {

    @Test
    void accepts_a_well_formed_pair() {
        ToolDescriptor d = new ToolDescriptor("AwsS3Tool", "Perform actions on AWS S3 buckets");
        assertThat(d.name()).isEqualTo("AwsS3Tool");
        assertThat(d.description()).isEqualTo("Perform actions on AWS S3 buckets");
    }

    @Test
    void accepts_64_char_name_and_rejects_65() {
        new ToolDescriptor("a".repeat(64), "d");
        assertThatThrownBy(() -> new ToolDescriptor("a".repeat(65), "d"))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("name");
                    assertThat(ex.getMessage()).contains("64");
                });
    }

    @Test
    void rejects_null_name() {
        assertThatThrownBy(() -> new ToolDescriptor(null, "d"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("name"));
    }

    @Test
    void rejects_blank_name() {
        assertThatThrownBy(() -> new ToolDescriptor("   ", "d"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("name"));
    }

    @Test
    void rejects_null_description() {
        assertThatThrownBy(() -> new ToolDescriptor("AwsS3Tool", null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("description"));
    }

    @Test
    void rejects_blank_description() {
        assertThatThrownBy(() -> new ToolDescriptor("AwsS3Tool", " "))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("description"));
    }
}
