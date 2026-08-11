package com.cognizant.emk.multiagent.infrastructure;

import com.cognizant.emk.multiagent.infrastructure.config.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @SpringBootTest} exemplar — boots the full Spring context (mock web environment;
 * no port binding) and asserts a real bean is wired. The template every infrastructure-layer
 * test follows when the full context is needed; persistence slice tests in EPIC-02 will
 * instead use {@code @DataJpaTest}.
 */
@SpringBootTest
class InfrastructureExemplarTest {

    @Autowired
    private ApplicationProperties properties;

    @Test
    void context_loads_and_typed_properties_are_bound() {
        assertThat(properties).isNotNull();
        assertThat(properties.api().basePath()).startsWith("/api/");
    }
}
