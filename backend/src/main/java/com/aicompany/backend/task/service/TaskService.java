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
            task.assignTo(requireProject(projectId));
        }

        return TaskResponse.from(repository.save(task));
    }

    /**
     * Puts an existing task in a project, or moves it to a different one.
     *
     * <p>Idempotent, unlike the project lifecycle transitions: assigning a task
     * to the project it is already in is a statement about the desired end state
     * and it is already true. There is no caller mistake to expose, which is what
     * made a repeated {@code archive} worth a 409 (ADR-004 §4).
     */
    public TaskResponse assignToProject(Long taskId, Long projectId) {

        Task task = repository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));

        // Rejects an archived project; the rule is on the entity, not here.
        task.assignTo(requireProject(projectId));

        return TaskResponse.from(repository.save(task));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private static List<TaskResponse> toResponses(List<Task> tasks) {
        return tasks.stream().map(TaskResponse::from).toList();
    }
}
