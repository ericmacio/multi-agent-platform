package com.cognizant.emk.multiagent.application.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @Test
    void has_exactly_two_values_in_documented_order() {
        // Order is locked in by EPIC-10's persistence mapping; reordering would change
        // ordinal values and silently shift persisted role columns if anyone ever
        // ever serializes them by ordinal.
        assertThat(Role.values()).containsExactly(Role.USER, Role.ASSISTANT);
    }
}
