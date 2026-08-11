package com.cognizant.emk.multiagent.application.chat;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TurnEventTest {

    @Test
    void started_accepts_two_non_null_uuids() {
        UUID uid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        TurnEvent.Started e = new TurnEvent.Started(uid, cid);
        assertThat(e.userMessageId()).isEqualTo(uid);
        assertThat(e.conversationId()).isEqualTo(cid);
    }

    @Test
    void started_rejects_null_ids() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TurnEvent.Started(null, UUID.randomUUID()))
                .withMessage("userMessageId");
        assertThatNullPointerException()
                .isThrownBy(() -> new TurnEvent.Started(UUID.randomUUID(), null))
                .withMessage("conversationId");
    }

    @Test
    void delta_accepts_non_null_text_including_empty_string() {
        assertThat(new TurnEvent.Delta("").text()).isEmpty();
        assertThat(new TurnEvent.Delta("hi").text()).isEqualTo("hi");
    }

    @Test
    void delta_rejects_null_text_with_field_text() {
        assertThatThrownBy(() -> new TurnEvent.Delta(null))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("text"));
    }

    @Test
    void completed_accepts_nullable_title_and_positive_count() {
        TurnEvent.Completed withTitle = new TurnEvent.Completed(
                UUID.randomUUID(), "first-turn", 2);
        assertThat(withTitle.title()).isEqualTo("first-turn");

        TurnEvent.Completed nullTitle = new TurnEvent.Completed(
                UUID.randomUUID(), null, 4);
        assertThat(nullTitle.title()).isNull();
    }

    @Test
    void completed_rejects_null_assistant_id() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TurnEvent.Completed(null, null, 2))
                .withMessage("assistantMessageId");
    }

    @Test
    void completed_rejects_zero_or_negative_message_count() {
        assertThatThrownBy(() -> new TurnEvent.Completed(UUID.randomUUID(), null, 0))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("messageCount"));
        assertThatThrownBy(() -> new TurnEvent.Completed(UUID.randomUUID(), null, -1))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("messageCount"));
    }

    @Test
    void error_accepts_non_blank_code_and_non_null_message_including_empty() {
        assertThat(new TurnEvent.Error("INTERNAL_ERROR", "").message()).isEmpty();
        assertThat(new TurnEvent.Error("LLM_UNAVAILABLE", "boom").code())
                .isEqualTo("LLM_UNAVAILABLE");
    }

    @Test
    void error_rejects_blank_code_and_null_message() {
        assertThatThrownBy(() -> new TurnEvent.Error("", "x"))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("code"));
        assertThatNullPointerException()
                .isThrownBy(() -> new TurnEvent.Error("CODE", null))
                .withMessage("message");
    }
}
