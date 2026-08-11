package com.cognizant.emk.multiagent.domain.mcp;

import com.cognizant.emk.multiagent.domain.shared.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownMcpServerExceptionTest {

    @Test
    void exposes_the_name_via_accessor_and_message() {
        UnknownMcpServerException ex = new UnknownMcpServerException("does-not-exist");

        assertThat(ex.name()).isEqualTo("does-not-exist");
        assertThat(ex.getMessage()).contains("does-not-exist");
    }

    @Test
    void is_a_business_exception() {
        assertThat(new UnknownMcpServerException("x")).isInstanceOf(BusinessException.class);
    }
}
