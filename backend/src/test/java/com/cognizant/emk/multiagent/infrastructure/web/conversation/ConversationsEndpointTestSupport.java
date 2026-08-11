package com.cognizant.emk.multiagent.infrastructure.web.conversation;

import com.cognizant.emk.multiagent.domain.agent.Agent;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import com.cognizant.emk.multiagent.domain.agent.AgentName;
import com.cognizant.emk.multiagent.domain.agent.AgentRepository;
import com.cognizant.emk.multiagent.domain.agent.MemorySize;
import com.cognizant.emk.multiagent.domain.agent.SamplingParams;
import com.cognizant.emk.multiagent.domain.agent.Team;
import com.cognizant.emk.multiagent.domain.conversation.Conversation;
import com.cognizant.emk.multiagent.domain.conversation.ConversationId;
import com.cognizant.emk.multiagent.domain.conversation.ConversationOwner;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.cognizant.emk.multiagent.domain.conversation.Message;
import com.cognizant.emk.multiagent.domain.conversation.MessageContent;
import com.cognizant.emk.multiagent.domain.conversation.MessageCount;
import com.cognizant.emk.multiagent.domain.conversation.MessageId;
import com.cognizant.emk.multiagent.domain.conversation.MessageRole;
import com.cognizant.emk.multiagent.domain.conversation.Title;
import com.cognizant.emk.multiagent.domain.user.Email;
import com.cognizant.emk.multiagent.domain.user.Role;
import com.cognizant.emk.multiagent.domain.user.User;
import com.cognizant.emk.multiagent.domain.user.UserId;
import com.cognizant.emk.multiagent.domain.user.UserRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test-side helpers shared by the per-endpoint integration tests
 * (US-10-006 .. US-10-010): seeding users / agents / conversations /
 * messages and obtaining a JWT via the real login endpoint. Kept out of
 * the production classpath; lives next to its consumers under
 * {@code src/test/java/.../infrastructure/web/conversation/}.
 */
final class ConversationsEndpointTestSupport {

    static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private ConversationsEndpointTestSupport() {}

    static UserId seedUser(UserRepository userRepository, String email, String password) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UserId id = new UserId(UUID.randomUUID());
        userRepository.save(new User(
                id, new Email(email), BCRYPT.encode(password),
                Role.STANDARD, false, false, now, now));
        return id;
    }

    static AgentId seedAgent(AgentRepository agentRepository, UserId owner, String name) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AgentId id = new AgentId(UUID.randomUUID());
        agentRepository.save(new Agent(
                id, owner, new AgentName(name),
                "d", "s", MemorySize.DEFAULT, SamplingParams.DEFAULTS,
                List.of(), List.of(), Team.EMPTY,
                now, now));
        return id;
    }

    static ConversationId seedConversation(
            ConversationRepository conversationRepository,
            AgentId agentId,
            UserId owner,
            String titleOrNull,
            int messageCount,
            OffsetDateTime when) {
        ConversationId id = new ConversationId(UUID.randomUUID());
        Title title = titleOrNull == null ? null : new Title(titleOrNull);
        conversationRepository.save(new Conversation(
                id, agentId,
                new ConversationOwner.UserOwner(owner),
                title,
                new MessageCount(messageCount),
                when, when));
        return id;
    }

    static MessageId seedMessage(
            ConversationRepository conversationRepository,
            ConversationId convId,
            MessageRole role,
            String content,
            OffsetDateTime when) {
        MessageId id = new MessageId(UUID.randomUUID());
        conversationRepository.appendMessage(new Message(
                id, convId, role, new MessageContent(content), when));
        return id;
    }

    static String login(MockMvc mockMvc, String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extract(body, "token");
    }

    static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new AssertionError("field '" + field + "' not found in: " + json);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
