package com.cognizant.emk.multiagent.persistence;

import com.cognizant.emk.multiagent.infrastructure.persistence.entity.AgentJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.ConversationJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.MessageJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.entity.UserJpa;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.AgentJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.ConversationJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.MessageJpaRepository;
import com.cognizant.emk.multiagent.infrastructure.persistence.springdata.UserJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end persistence test proving the database-level cascades enforce the hard-delete
 * semantics of {@code REQ-USR-006} (user delete removes everything they own) and
 * {@code REQ-AGT-010} (agent delete removes its conversations and messages).
 *
 * <p>Uses {@link EntityManager#persist(Object)} directly (rather than Spring Data's
 * {@code save()}) because our JPA entities carry manually-assigned UUID primary keys.
 * Spring Data's "is-this-entity-new" check looks at the ID; with a non-null ID it switches
 * from {@code persist()} to {@code merge()}, leaving the original instance transient and
 * tripping cascade-association validation when we attach it to a child entity.
 */
@Transactional
class CascadeIntegrationTest extends PostgresIntegrationTest {

    @Autowired private UserJpaRepository userRepo;
    @Autowired private AgentJpaRepository agentRepo;
    @Autowired private ConversationJpaRepository conversationRepo;
    @Autowired private MessageJpaRepository messageRepo;
    @Autowired private EntityManager em;

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
    private static final String BCRYPT_DUMMY = "$2a$10$abcdefghijklmnopqrstuuJqf2QHm/rEZx8L0a3T1aPgI8Vm/tnsW";

    private UserJpa user;
    private AgentJpa agent;
    private ConversationJpa conversation;

    @BeforeEach
    void seedGraph() {
        user = new UserJpa(UUID.randomUUID(),
                "cascade-" + UUID.randomUUID() + "@example.test", BCRYPT_DUMMY,
                "STANDARD", false, false, NOW, NOW);
        em.persist(user);

        agent = new AgentJpa(UUID.randomUUID(), user,
                "agent-" + System.nanoTime(), "desc", "prompt",
                12, null, null, null, null, NOW, NOW);
        em.persist(agent);

        conversation = new ConversationJpa(UUID.randomUUID(), agent, user, null,
                null, 0, NOW, NOW);
        em.persist(conversation);

        em.persist(new MessageJpa(UUID.randomUUID(), conversation, "USER", "hi", NOW));
        em.persist(new MessageJpa(UUID.randomUUID(), conversation, "ASSISTANT", "hello", NOW));

        em.flush();
        em.clear();
    }

    @Test
    void deleting_an_agent_cascades_to_conversations_and_messages() {
        UUID conversationId = conversation.getId();
        UUID userId = user.getId();

        agentRepo.deleteById(agent.getId());
        em.flush();
        em.clear();

        assertThat(conversationRepo.findById(conversationId)).isEmpty();
        assertThat(messageRepo.count()).isEqualTo(0);
        assertThat(userRepo.findById(userId)).isPresent();
    }

    @Test
    void deleting_a_user_cascades_to_agents_conversations_and_messages() {
        UUID agentId = agent.getId();
        UUID conversationId = conversation.getId();

        userRepo.deleteById(user.getId());
        em.flush();
        em.clear();

        assertThat(agentRepo.findById(agentId)).isEmpty();
        assertThat(conversationRepo.findById(conversationId)).isEmpty();
        assertThat(messageRepo.count()).isEqualTo(0);
    }
}
