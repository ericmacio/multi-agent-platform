package com.cognizant.emk.multiagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that the Spring context loads with the EPIC-01 wiring. */
@SpringBootTest
class ApplicationContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void context_loads() {
        assertThat(context).isNotNull();
        assertThat(context.getId()).isNotBlank();
    }
}
