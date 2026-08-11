package com.cognizant.emk.multiagent.domain;

import com.cognizant.emk.multiagent.domain.shared.BusinessException;
import com.cognizant.emk.multiagent.domain.shared.ConflictException;
import com.cognizant.emk.multiagent.domain.shared.ForbiddenException;
import com.cognizant.emk.multiagent.domain.shared.NotFoundException;
import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 + AssertJ exemplar — no Spring context, no Mockito. The template every
 * domain-layer test follows.
 */
class DomainExemplarTest {

    @Test
    void shared_exceptions_extend_business_exception() {
        assertThat(new ValidationException("nope"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("nope");

        assertThat(new NotFoundException("nope")).isInstanceOf(BusinessException.class);
        assertThat(new ConflictException("nope")).isInstanceOf(BusinessException.class);
        assertThat(new ForbiddenException("nope")).isInstanceOf(BusinessException.class);
    }
}
