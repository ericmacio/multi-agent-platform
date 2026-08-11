package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.RateLimitConfigJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentMcpJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentTeamJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentToolJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.ApiKeyJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.ConversationJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.JwtDenylistJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.MessageJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.RateLimitConfigJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.UserJpaRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every Spring Data JPA repository interface is registered as a Spring bean
 * and that {@code RateLimitConfigJpaRepository} can read the row seeded by V003.
 *
 * <p>Overrides {@code spring.flyway.locations} to {@code classpath:db/migration} only
 * so the EPIC-13 test-only V900 override (which bumps the rate-limit counters for
 * the broader integration suite) is NOT applied here.
 */
class RepositoriesContextTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void productionMigrationsOnly(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired private UserJpaRepository userRepo;
    @Autowired private AgentJpaRepository agentRepo;
    @Autowired private AgentToolJpaRepository agentToolRepo;
    @Autowired private AgentMcpJpaRepository agentMcpRepo;
    @Autowired private AgentTeamJpaRepository agentTeamRepo;
    @Autowired private ConversationJpaRepository conversationRepo;
    @Autowired private MessageJpaRepository messageRepo;
    @Autowired private ApiKeyJpaRepository apiKeyRepo;
    @Autowired private JwtDenylistJpaRepository jwtDenylistRepo;
    @Autowired private RateLimitConfigJpaRepository rateLimitConfigRepo;

    @Test
    void all_repositories_are_wired() {
        assertThat(userRepo).isNotNull();
        assertThat(agentRepo).isNotNull();
        assertThat(agentToolRepo).isNotNull();
        assertThat(agentMcpRepo).isNotNull();
        assertThat(agentTeamRepo).isNotNull();
        assertThat(conversationRepo).isNotNull();
        assertThat(messageRepo).isNotNull();
        assertThat(apiKeyRepo).isNotNull();
        assertThat(jwtDenylistRepo).isNotNull();
        assertThat(rateLimitConfigRepo).isNotNull();
    }

    @Test
    void rate_limit_config_repository_returns_seeded_row() {
        Optional<RateLimitConfigJpa> seeded = rateLimitConfigRepo.findById((short) 1);
        assertThat(seeded).isPresent();
        assertThat(seeded.get().getPerMinute()).isEqualTo(10);
        assertThat(seeded.get().getPerHour()).isEqualTo(50);
    }
}
