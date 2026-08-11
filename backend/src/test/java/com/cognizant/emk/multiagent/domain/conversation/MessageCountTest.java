package com.cognizant.emk.multiagent.domain.conversation;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCountTest {

    @Test
    void accepts_boundary_values_0_and_64() {
        assertThat(new MessageCount(0).value()).isEqualTo(0);
        assertThat(new MessageCount(64).value()).isEqualTo(64);
    }

    @Test
    void empty_singleton_is_zero() {
        assertThat(MessageCount.EMPTY.value()).isEqualTo(0);
    }

    @Test
    void rejects_negative_with_field_message_count() {
        assertThatThrownBy(() -> new MessageCount(-1))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("messageCount");
                    assertThat(ex.getMessage()).contains("0").contains("64");
                });
    }

    @Test
    void rejects_65_with_field_message_count() {
        assertThatThrownBy(() -> new MessageCount(65))
                .isInstanceOfSatisfying(ValidationException.class, ex ->
                        assertThat(ex.field()).hasValue("messageCount"));
    }

    @Test
    void is_full_is_true_only_at_64() {
        assertThat(new MessageCount(0).isFull()).isFalse();
        assertThat(new MessageCount(63).isFull()).isFalse();
        assertThat(new MessageCount(64).isFull()).isTrue();
    }

    @Test
    void increment_or_throw_returns_a_new_instance_with_value_plus_one() {
        ConversationId id = new ConversationId(UUID.randomUUID());
        MessageCount bumped = new MessageCount(5).incrementOrThrow(id);
        assertThat(bumped.value()).isEqualTo(6);
    }

    @Test
    void increment_or_throw_throws_conversation_full_at_cap() {
        ConversationId id = new ConversationId(
                UUID.fromString("a9b9bb11-1234-4abc-9def-1234567890ab"));
        assertThatThrownBy(() -> new MessageCount(64).incrementOrThrow(id))
                .isInstanceOf(ConversationFullException.class)
                .hasMessageContaining("a9b9bb11-1234-4abc-9def-1234567890ab")
                .hasMessageContaining("64");
    }
}
