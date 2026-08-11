package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.application.chat.DelegationService.DelegationResult;
import com.cognizant.emk.multiagent.domain.agent.AgentId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DelegationResultTest {

    private static final AgentId TARGET = new AgentId(UUID.randomUUID());

    @Test
    void accepts_a_well_formed_result() {
        DelegationResult r = new DelegationResult(TARGET, "hello from B");

        assertThat(r.targetMemberId()).isEqualTo(TARGET);
        assertThat(r.text()).isEqualTo("hello from B");
    }

    @Test
    void accepts_an_empty_text() {
        DelegationResult r = new DelegationResult(TARGET, "");

        assertThat(r.text()).isEmpty();
    }

    @Test
    void rejects_null_target_member_id() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DelegationResult(null, "anything"))
                .withMessage("targetMemberId");
    }

    @Test
    void rejects_null_text() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DelegationResult(TARGET, null))
                .withMessage("text");
    }
}
