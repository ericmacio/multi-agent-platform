package com.cognizant.emk.multiagent.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Load-bearing contract test of EPIC-02. The Spring context boots with
 * {@code spring.jpa.hibernate.ddl-auto=validate}; if any JPA entity in
 * {@code infrastructure/persistence/entity/} disagrees with the migrated schema
 * (column missing, wrong type, wrong nullability, etc.), startup fails — and so
 * does this test.
 *
 * <p>The assertions just check that every expected entity is present in Hibernate's
 * metamodel. The real verification is the successful boot itself.
 */
class HibernateValidateContractTest extends PostgresIntegrationTest {

    private static final Set<String> EXPECTED_ENTITIES = Set.of(
            "UserJpa",
            "AgentJpa",
            "AgentToolJpa",
            "AgentMcpJpa",
            "AgentTeamJpa",
            "ConversationJpa",
            "MessageJpa",
            "ApiKeyJpa",
            "JwtDenylistJpa",
            "RateLimitConfigJpa"
    );

    @Autowired
    private EntityManager entityManager;

    @Test
    void every_documented_entity_is_registered() {
        Set<String> entityNames = entityManager.getMetamodel().getEntities().stream()
                .map(EntityType::getName)
                .collect(Collectors.toSet());
        assertThat(entityNames).containsAll(EXPECTED_ENTITIES);
    }
}
