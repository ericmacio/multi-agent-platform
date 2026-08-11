package com.cognizant.emk.multiagent.domain.agent;

import com.cognizant.emk.multiagent.domain.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamTest {

    private static AgentId id() {
        return new AgentId(UUID.randomUUID());
    }

    @Test
    void empty_constant_is_reachable_and_empty() {
        assertThat(Team.EMPTY.members()).isEmpty();
    }

    @Test
    void preserves_insertion_order_when_input_has_no_duplicates() {
        AgentId a = id();
        AgentId b = id();
        AgentId c = id();
        Team team = new Team(List.of(a, b, c));
        assertThat(team.members()).containsExactly(a, b, c);
    }

    @Test
    void rejects_null_entries_with_field_team() {
        AgentId a = id();
        assertThatThrownBy(() -> new Team(Arrays.asList(a, null)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("team");
                    assertThat(ex.getMessage()).contains("null");
                });
    }

    @Test
    void rejects_duplicate_entries_with_field_team() {
        AgentId a = id();
        assertThatThrownBy(() -> new Team(List.of(a, a)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.field()).hasValue("team");
                    assertThat(ex.getMessage()).contains(a.value().toString());
                });
    }

    @Test
    void members_list_is_unmodifiable() {
        AgentId a = id();
        Team team = new Team(List.of(a));
        assertThatThrownBy(() -> team.members().add(id()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejects_null_members_argument() {
        assertThatThrownBy(() -> new Team(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("members");
    }
}
