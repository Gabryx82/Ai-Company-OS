package com.aicompany.backend.project;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.project.service.ProjectService;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lock protocol as a property of the code, not of a database schedule.
 *
 * <p>Two things cannot be observed from a concurrency test at all. One is the
 * <em>order</em> in which locks are taken: a schedule that happens to work proves
 * nothing about the ordering rule that keeps it working, and with two shared
 * locks there is no deadlock available to provoke. The other is the absence of a
 * self-invocation, which by definition leaves no trace at runtime.
 *
 * <p>Both are asserted here against doubles, so each one fails for exactly one
 * reason.
 */
class ProjectLockProtocolTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final ProjectRepository projectRepository = mock(ProjectRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final TaskService taskService =
            new TaskService(taskRepository, projectRepository, agentRepository);

    /**
     * AC-13 and AC-20, rules L0, L4 and L5.
     *
     * <p>The destination is given a <em>lower</em> id than the source on purpose:
     * with ascending-id ordering the destination is locked first, while an
     * implementation that ordered by role -- source, then destination -- would
     * take them the other way round and be caught here. That is the whole reason
     * this test exists, since both orders behave identically at runtime until the
     * day two exclusive locks meet.
     *
     * <p>It also pins the one order that is not a convention: the task row before
     * any project row. Which projects the decision depends on is only knowable
     * from the task row, so reading it unlocked is the defect L0 closes.
     */
    @Test
    void locksAreTakenTaskFirstAndThenProjectsByAscendingId() {

        Project source = project(7L, "Company OS");
        Project destination = project(3L, "Planner");

        Task task = new Task("Wire the planner", null, "OPEN", "HIGH");
        task.assignTo(source);
        ReflectionTestUtils.setField(task, "id", 1L);

        when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findByIdForShare(7L)).thenReturn(Optional.of(source));
        when(projectRepository.findByIdForShare(3L)).thenReturn(Optional.of(destination));
        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        taskService.assignToProject(1L, 3L, Preconditions.at(0));

        InOrder protocolOrder = inOrder(taskRepository, projectRepository);
        protocolOrder.verify(taskRepository).findByIdForUpdate(1L);
        protocolOrder.verify(projectRepository).findByIdForShare(3L);
        protocolOrder.verify(projectRepository).findByIdForShare(7L);

        verify(projectRepository, never()).findById(anyLong());
        verify(taskRepository, never()).findById(anyLong());
    }

    /**
     * L4: a reassignment depends on <em>both</em> projects -- the source, because
     * a task inside an archived project does not move, and the destination,
     * because an archived project receives no new work. Locking only one of them
     * leaves the rule that depends on the other deciding on unlocked state.
     */
    @Test
    void aReassignmentLocksBothTheSourceAndTheDestination() {

        Project source = project(7L, "Company OS");
        Project destination = project(3L, "Planner");

        Task task = new Task("Wire the planner", null, "OPEN", "HIGH");
        task.assignTo(source);
        ReflectionTestUtils.setField(task, "id", 1L);

        when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findByIdForShare(7L)).thenReturn(Optional.of(source));
        when(projectRepository.findByIdForShare(3L)).thenReturn(Optional.of(destination));
        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        taskService.assignToProject(1L, 3L, Preconditions.at(0));

        verify(projectRepository).findByIdForShare(7L);
        verify(projectRepository).findByIdForShare(3L);
    }

    /**
     * L4 again, the degenerate case: source and destination are the same project,
     * so the set collapses to one row and one lock. Taking it twice would be
     * harmless but would mean the set is not being deduplicated, which stops being
     * harmless the moment the set is larger.
     */
    @Test
    void reassigningToTheSameProjectLocksThatRowOnlyOnce() {

        Project project = project(7L, "Company OS");

        Task task = new Task("Wire the planner", null, "OPEN", "HIGH");
        task.assignTo(project);
        ReflectionTestUtils.setField(task, "id", 1L);

        when(taskRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(task));
        when(projectRepository.findByIdForShare(7L)).thenReturn(Optional.of(project));
        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        taskService.assignToProject(1L, 7L, Preconditions.at(0));

        verify(projectRepository).findByIdForShare(7L);
    }

    /**
     * AC-18, TD-24, the behavioural half.
     *
     * <p>The write paths used to resolve the project through the public
     * {@code readOnly = true} {@code findById}, invoked through {@code this.}, and
     * worked only because self-invocation bypasses the proxy and with it the
     * read-only flag. Switching the transaction infrastructure to AspectJ proxies,
     * or simply extracting that lookup, would have broken them.
     *
     * <p>Asserting the absence of a self-invocation directly is not possible --
     * it leaves no trace. What can be asserted is the property that made it a
     * hazard: the write paths must reach the repository through the locked lookup
     * and never through the read-only one.
     */
    @Test
    void theProjectWritePathsNeverUseTheReadOnlyLookup() {

        ProjectRepository repository = mock(ProjectRepository.class);
        ProjectService service = new ProjectService(repository);

        Project project = project(7L, "Company OS");
        when(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(project));
        when(repository.saveAndFlush(any(Project.class))).thenAnswer(call -> call.getArgument(0));

        service.archive(7L, Preconditions.at(0));

        verify(repository).findByIdForUpdate(7L);
        verify(repository, never()).findById(anyLong());
    }

    /**
     * AC-18, the structural half: the lookup the write paths use is private and
     * carries no {@code @Transactional} of its own.
     *
     * <p>This is what makes TD-24 not merely absent but <em>inexpressible</em>
     * here. A private method cannot carry a transactional attribute that a proxy
     * would have had to apply, so there is no read-only flag left for a
     * self-invocation to silently discard.
     */
    @Test
    void theProjectWriteLookupIsPrivateAndCarriesNoTransactionalAttribute() throws Exception {

        Method lookup = ProjectService.class.getDeclaredMethod(
                "lockForWrite", Long.class, Precondition.class);

        assertThat(Modifier.isPrivate(lookup.getModifiers()))
                .as("a private lookup cannot carry a transactional attribute, so TD-24 is not "
                        + "expressible through it")
                .isTrue();

        assertThat(lookup.isAnnotationPresent(Transactional.class))
                .as("annotating it would put back exactly the attribute self-invocation discards")
                .isFalse();

        assertThatCode(() -> ProjectService.class.getDeclaredMethod("findById", Long.class))
                .as("the read-only lookup stays, for reads")
                .doesNotThrowAnyException();
    }

    private static Project project(Long id, String name) {
        Project project = new Project(name, null);
        ReflectionTestUtils.setField(project, "id", id);
        return project;
    }
}
