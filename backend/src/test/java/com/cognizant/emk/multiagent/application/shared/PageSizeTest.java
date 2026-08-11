package com.cognizant.emk.multiagent.application.shared;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageSizeTest {

    @Test
    void from_query_param_returns_default_when_argument_is_null() {
        assertThat(PageSize.fromQueryParam(null).value()).isEqualTo(PageSize.DEFAULT);
        assertThat(PageSize.DEFAULT).isEqualTo(20);
    }

    @Test
    void from_query_param_accepts_boundary_values_1_and_100() {
        assertThat(PageSize.fromQueryParam(1).value()).isEqualTo(1);
        assertThat(PageSize.fromQueryParam(100).value()).isEqualTo(100);
    }

    @Test
    void from_query_param_rejects_0_with_field_pageSize() {
        assertThatThrownBy(() -> PageSize.fromQueryParam(0))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("pageSize");
                    assertThat(ex.getMessage()).contains("1").contains("100");
                });
    }

    @Test
    void from_query_param_rejects_101_with_field_pageSize() {
        assertThatThrownBy(() -> PageSize.fromQueryParam(101))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("pageSize"));
    }

    @Test
    void from_query_param_rejects_negative_with_field_pageSize() {
        assertThatThrownBy(() -> PageSize.fromQueryParam(-1))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("pageSize"));
    }
}
