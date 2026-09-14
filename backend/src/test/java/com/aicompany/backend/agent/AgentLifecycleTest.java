package com.aicompany.backend.agent;

import com.aicompany.backend.agent.exception.IllegalAgentStateTransitionException;
import com.aicompany.backend.agent.exception.InactiveAgentIsImmutableException;
import com.aicompany.backend.agent.model.Agent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle rules on their own, with no Spring context and no database:
 * these are properties of the domain object, not of the persistence layer.
 *
 * <p>Deliberately the same shape as {@code ProjectLifecycleTest}. The two
 * registries answer the same questions and should be readable side by side; the
 * only difference is that one keeps its state in an enum and the other in a
 * boolean, which is TD-31 and not a difference in behaviour.
 */
class AgentLifecycleTest {

    @Test
    void aNewAgentIsActive() {
        assertThat(new Agent("Code Architect", "Engineer", "architecture").isActive()).isTrue();
    }

    @Test
    void deactivatingTakesAnAgentOutOfTheRegistry() {

        Agent agent = new Agent("Code Architect", "Engineer", "architecture");
        agent.deactivate();

        assertThat(agent.isActive()).isFalse();
    }

    /**
     * ADR-004 §4, applied here: a repeated transition is almost always a caller
     * mistake, and absorbing it silently makes the state machine unverifiable.
     */
    @Test
    void deactivatingTwiceIsRejected() {

        Agent agent = new Agent("Code Architect", "Engineer", "architecture");
        agent.deactivate();

        assertThatThrownBy(agent::deactivate)
                .isInstanceOf(IllegalAgentStateTransitionException.class)
                .hasMessageContaining("already inactive");
    }

    @Test
    void activatingAnActiveAgentIsRejected() {
        assertThatThrownBy(new Agent("Code Architect", "Engineer", "architecture")::activate)
                .isInstanceOf(IllegalAgentStateTransitionException.class);
    }

    @Test
    void activatingBringsADeactivatedAgentBack() {

        Agent agent = new Agent("Code Architect", "Engineer", "architecture");
        agent.deactivate();
        agent.activate();

        assertThat(agent.isActive()).isTrue();
    }

    /**
     * ADR-004 §8, applied here: something out of the working registry that still
     * accepts edits is not out of anything -- and it goes on holding its name in
     * the unique index while doing so.
     */
    @Test
    void anInactiveAgentCannotBeEdited() {

        Agent agent = new Agent("Code Architect", "Engineer", "architecture");
        agent.deactivate();

        assertThatThrownBy(() -> agent.updateDetails("Renamed", "Other", "other"))
                .isInstanceOf(InactiveAgentIsImmutableException.class)
                .hasMessageContaining("activate it first");

        assertThat(agent.getName()).isEqualTo("Code Architect");
        assertThat(agent.getRole()).isEqualTo("Engineer");
        assertThat(agent.getSpecialization()).isEqualTo("architecture");
    }

    @Test
    void activatingMakesAnAgentEditableAgain() {

        Agent agent = new Agent("Code Architect", "Engineer", "architecture");
        agent.deactivate();
        agent.activate();
        agent.updateDetails("Renamed", "Other", "other");

        assertThat(agent.getName()).isEqualTo("Renamed");
        assertThat(agent.isActive()).isTrue();
    }

    @Test
    void updatingDetailsDoesNotTouchTheLifecycle() {

        Agent agent = new Agent("Code Architect", "Engineer", "architecture");
        agent.updateDetails("Renamed", "Other", "other");

        assertThat(agent.isActive()).isTrue();
    }
}
