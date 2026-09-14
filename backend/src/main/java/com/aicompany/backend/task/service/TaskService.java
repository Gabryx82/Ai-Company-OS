package com.aicompany.backend.task.service;

import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Application service for tasks.
 *
 * <p>It returns response records rather than entities, which the project service
 * does not need to do. The reason is the lazy {@code Task.project} reference:
 * with {@code spring.jpa.open-in-view=false} the persistence context is closed
 * by the time a controller sees the entity, so the mapping has to happen here,
 * inside the transaction. Keeping the entity inside this boundary also means the
 * only way to change a task's project is {@link #assignToProject}, where the
 * rules are.
 *
 * <p>The rules themselves are not here: refusing an archived project lives on
 * {@link Task#assignTo(Project)}, next to the state it depends on.
 */
@Service
@Transactional
public class TaskService {

    private final TaskRepository repository;
    private final ProjectRepository projectRepository;

    public TaskService(TaskRepository repository, ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> findAll() {
        return toResponses(repository.findAllWithProject());
    }

    /**
     * The tasks of one project, oldest first.
     *
     * <p>The project is looked up first so that an unknown identifier is a 404
     * instead of an empty list -- "this project has no tasks" and "there is no
     * such project" are different answers and a client acts differently on them.
     *
     * <p>An archived project answers normally. Archiving takes a project out of
     * the working registry; it does not make its history unreadable, and reads
     * are not what ADR-004 §8 restricts.
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> findAllByProject(Long projectId) {

        // No lock: rule L6. A read never delays a lifecycle transition.
        requireProject(projectId);
        return toResponses(repository.findAllByProjectId(projectId));
    }

    /**
     * Creates a task, optionally inside a project.
     *
     * <p>A rejected project means no task: the assignment happens before the
     * insert, in the same transaction, so a client never has to clean up a task
     * that was created and then failed to be placed.
     */
    public TaskResponse create(String title,
                               String description,
                               String status,
                               String priority,
                               Long projectId) {

        Task task = new Task(title, description, status, priority);

        if (projectId != null) {
            // Rule L3: one project is involved, the destination, and it is locked
            // shared before its state decides anything. No L0 here and that is not
            // an exception to it -- L0 protects an existing row's association, and
            // a row nobody else can see yet has none to protect.
            task.assignTo(requireProjectForDecision(projectId));
        }

        return TaskResponse.from(repository.save(task));
    }

    /**
     * Puts an existing task in a project, or moves it to a different one.
     *
     * <p>The order of what happens here is the lock protocol of ADR-006 §4, and
     * it is not interchangeable:
     *
     * <ol>
     *   <li><strong>L0</strong> -- the task row is locked exclusively
     *       <em>before</em> its association is read. Everything below decides on
     *       that row, and nothing can change it underneath in the meantime.</li>
     *   <li>the source project is read from the row that was just locked;</li>
     *   <li><strong>L4</strong> -- the set of projects the decision depends on is
     *       built: the source, because a task inside an archived project does not
     *       move, and the destination, because an archived project receives no
     *       new work;</li>
     *   <li><strong>L5</strong> -- that set is deduplicated (source and
     *       destination can be the same project, and then it is one lock) and
     *       ordered by ascending id, so two requests locking overlapping sets
     *       cannot wait on each other;</li>
     *   <li><strong>L2</strong> -- each of them is locked shared, in that order,
     *       and only then are the rules applied.</li>
     * </ol>
     *
     * <p>Reversing steps 1 and 3 is not a matter of style. It is the defect L0
     * exists to close: a transaction that reads the association before locking it
     * can commit a write against a task that has meanwhile moved into an archived
     * project, having evaluated the frozen rule against a project the task left.
     *
     * <p>Idempotent, unlike the project lifecycle transitions: assigning a task
     * to the project it is already in is a statement about the desired end state
     * and it is already true. There is no caller mistake to expose, which is what
     * made a repeated {@code archive} worth a 409 (ADR-004 §4).
     *
     * <p><strong>What this does not do.</strong> L0 serialises writes to the same
     * task, so each of them decides on fresh state -- it does not <em>report</em>
     * a stale intent. Two callers reassigning the same task still resolve
     * last-write-wins, and the first is not told it was overtaken. Detecting that
     * needs optimistic concurrency in the HTTP contract, not a lock, and it is
     * TD-30, the twin of TD-28 on the other entity.
     */
    public TaskResponse assignToProject(Long taskId, Long projectId) {

        // L0. Before anything is read about where this task lives.
        Task task = repository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        Long sourceId = task.getProjectId();

        // L4 and L5: build the set, deduplicate it, order it by ascending id.
        SortedSet<Long> projectsToLock = new TreeSet<>();
        if (sourceId != null) {
            projectsToLock.add(sourceId);
        }
        projectsToLock.add(projectId);

        // L2, in that order. The result of the destination lookup is the instance
        // handed to the entity; the source is resolved through the same
        // persistence context, so the guard reads locked state rather than the
        // snapshot the task row carried.
        Project target = null;
        for (Long id : projectsToLock) {
            Project locked = requireProjectForDecision(id);
            if (id.equals(projectId)) {
                target = locked;
            }
        }

        // Rules on the entity, not here.
        task.assignTo(target);

        return TaskResponse.from(repository.save(task));
    }

    /** Unlocked lookup, for reads only (L6). */
    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    /**
     * Lookup for a write path whose correctness depends on the project's
     * lifecycle state: rule L2, a shared lock held to commit.
     *
     * <p>Shared, so that two tasks being assigned to the same active project do
     * not serialise against each other -- they are not in conflict. What it does
     * exclude is a lifecycle transition running alongside the decision, which is
     * what makes the guarantee "at the commit of this write, the project was in
     * the state the rules were applied to" rather than "was checked at some
     * point". That is TD-25.
     */
    private Project requireProjectForDecision(Long projectId) {
        return projectRepository.findByIdForShare(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private static List<TaskResponse> toResponses(List<Task> tasks) {
        return tasks.stream().map(TaskResponse::from).toList();
    }
}
