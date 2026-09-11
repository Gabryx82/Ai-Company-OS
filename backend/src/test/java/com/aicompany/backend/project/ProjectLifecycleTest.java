package com.aicompany.backend.project;

import com.aicompany.backend.project.exception.IllegalProjectStateTransitionException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle rules on their own, with no Spring context and no database:
 * these are properties of the domain object, not of the persistence layer.
 */
class ProjectLifecycleTest {

    @Test
    void aNewProjectIsActive() {
        assertThat(new Project("Company OS", "the first one").getStatus())
                .isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void archivingMovesAnActiveProjectOutOfTheRegistry() {

        Project project = new Project("Company OS", null);
        project.archive();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(project.isArchived()).isTrue();
    }

    @Test
    void archivingTwiceIsRejected() {

        Project project = new Project("Company OS", null);
        project.archive();

        assertThatThrownBy(project::archive)
                .isInstanceOf(IllegalProjectStateTransitionException.class)
                .hasMessageContaining("ARCHIVED")
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    void restoringBringsAnArchivedProjectBack() {

        Project project = new Project("Company OS", null);
        project.archive();
        project.restore();

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.isArchived()).isFalse();
    }

    @Test
    void restoringAnActiveProjectIsRejected() {

        assertThatThrownBy(new Project("Company OS", null)::restore)
                .isInstanceOf(IllegalProjectStateTransitionException.class);
    }

    @Test
    void updatingDetailsDoesNotTouchTheLifecycle() {

        Project project = new Project("Company OS", "before");
        project.archive();
        project.updateDetails("Company OS renamed", "after");

        assertThat(project.getName()).isEqualTo("Company OS renamed");
        assertThat(project.getDescription()).isEqualTo("after");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void theStatusSetIsClosedAndHasNoDeletedMember() {

        // Archiving replaces deletion; a DELETED status would reintroduce it by
        // the back door. See ADR-004.
        assertThat(ProjectStatus.values())
                .containsExactly(ProjectStatus.ACTIVE, ProjectStatus.ARCHIVED);
    }
}
