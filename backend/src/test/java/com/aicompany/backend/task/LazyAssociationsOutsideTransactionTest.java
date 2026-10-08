package com.aicompany.backend.task;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.task.service.TaskService;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 -- TD-26: the lazy path, exercised <em>outside</em> a transaction.
 *
 * <p>{@code Task.project} and {@code Task.agent} are {@code LAZY} and
 * {@code open-in-view} is off (ADR-005 §9), so an entity that leaves the service
 * cannot load them: the rule is "map where the data is". TASK-004 pinned only
 * that reading the project's id does not initialise the proxy, inside a
 * transaction. Here nothing is transactional: the entity comes back detached,
 * its identifiers are still readable, traversing it fails loudly, and the paths
 * the API really takes -- the service and the endpoint -- answer with the
 * associations resolved. If someone switched the associations to {@code EAGER},
 * turned on open-in-view, or let an entity reach a controller, this would say so.
 */
class LazyAssociationsOutsideTransactionTest extends AbstractPostgresTest {

    @Autowired
    private TaskRepository tasks;
    @Autowired
    private ProjectRepository projects;
    @Autowired
    private AgentRepository agents;
    @Autowired
    private TaskService taskService;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void aDetachedTaskKeepsItsIdentifiersAndRefusesToLoadItsAssociations() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("this test is about what happens with no transaction around").isFalse();

        Long projectId = projects.saveAndFlush(new Project("Lazy Outside " + System.nanoTime(), null)).getId();
        Long agentId = agents.saveAndFlush(new Agent("lazy-outside-" + System.nanoTime(), "BACKEND", "lazy probe")).getId();
        Long taskId = tasks.saveAndFlush(new Task("Lazy outside a transaction", null, TaskStatus.OPEN, TaskPriority.HIGH)).getId();
        taskService.assignToProject(taskId, projectId, Preconditions.at(tasks.findById(taskId).orElseThrow().getVersion()));
        taskService.assignToAgent(taskId, agentId, Preconditions.at(tasks.findById(taskId).orElseThrow().getVersion()));

        // The repository's own read-only transaction ends with the call: what comes back is detached.
        Task detached = tasks.findById(taskId).orElseThrow();

        assertThat(Hibernate.isInitialized(detached.getProject())).isFalse();
        assertThat(Hibernate.isInitialized(detached.getAgent())).isFalse();
        assertThat(detached.getProjectId()).isEqualTo(projectId);
        assertThat(detached.getAgentId()).isEqualTo(agentId);
        assertThatThrownBy(() -> detached.getProject().getName()).isInstanceOf(LazyInitializationException.class);
        assertThatThrownBy(() -> detached.getAgent().getName()).isInstanceOf(LazyInitializationException.class);

        // The service maps inside its transaction, so its caller needs none.
        TaskResponse mapped = taskService.findById(taskId).body();
        assertThat(mapped.projectId()).isEqualTo(projectId);
        assertThat(mapped.agentId()).isEqualTo(agentId);

        // And the endpoint, with no transaction anywhere in the test.
        mockMvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.agentId").value(agentId));
        mockMvc.perform(get("/api/projects/" + projectId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value(projectId));
    }
}
