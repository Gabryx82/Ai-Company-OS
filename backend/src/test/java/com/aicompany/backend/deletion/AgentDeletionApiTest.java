package com.aicompany.backend.deletion;

import com.aicompany.backend.harness.service.AgentTemplates;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 30 (ADR-035 §2): agents are deleted for real -- an admin's decision,
 * under the agent's tag, with every relation decided: configuration and links
 * go, tasks stay unassigned, runs stay with the agent's name, sub-agents become
 * top-level or go too when asked. Work in progress blocks the delete.
 */
class AgentDeletionApiTest extends AbstractPostgresTest {

    private static final String PREFIX = "Del Agent ";
    private static final String BLOCKED = "urn:ai-company-os:problem:deletion-blocked";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentTemplates templates;

    private final JsonMapper json = JsonMapper.builder().build();
    private String admin;

    @BeforeEach
    void anAdminSession() throws Exception {
        jdbc.update("UPDATE app_users SET failed_attempts = 0, locked_until = NULL");
        admin = AdminSession.bearer(mockMvc);
        jdbc.update("DELETE FROM security_events");
    }

    @AfterEach
    void removeTheFixtures() {
        jdbc.update("DELETE FROM task_runs WHERE agent_name LIKE 'Del Agent %' OR agent_id IN "
                + "(SELECT id FROM agents WHERE name LIKE 'Del Agent %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'Del Task %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'Del Task %'");
        jdbc.update("UPDATE agents SET parent_id = NULL WHERE name LIKE 'Del Agent %'");
        jdbc.update("DELETE FROM agents WHERE name LIKE 'Del Agent %'");
        jdbc.update("UPDATE projects SET status = 'ACTIVE' WHERE name LIKE 'Del Project %'");
    }

    // --- fixtures --------------------------------------------------------------------------

    private long agent(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + PREFIX + name + "\",\"role\":\"Backend Engineer\",\"specialization\":\"Java\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    private void parentOf(long child, long parent) {
        jdbc.update("UPDATE agents SET parent_id = ? WHERE id = ?", parent, child);
    }

    private long task(String title, Long agentId, String status, Long projectId) {
        return jdbc.queryForObject("INSERT INTO tasks (title, status, priority, agent_id, project_id) "
                + "VALUES (?, ?, 'LOW', ?, ?) RETURNING id", Long.class, "Del Task " + title, status, agentId, projectId);
    }

    private void run(long taskId, long agentId, String status) {
        boolean done = status.equals("SUCCEEDED");
        jdbc.update("INSERT INTO task_runs (task_id, agent_id, status, system_prompt, user_prompt, correlation_id, "
                + "requested_by, created_at, output, finish_reason, finished_at) VALUES (?, ?, ?, 's', 'u', ?, 'operator', now(), ?, ?, ?)",
                taskId, agentId, status, "c-" + System.nanoTime(), done ? "ok" : null, done ? "stop" : null,
                done ? java.sql.Timestamp.from(Instant.now()) : null);
    }

    private String tag(long agentId) throws Exception {
        return mockMvc.perform(get("/api/agents/" + agentId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private boolean exists(long agentId) {
        return jdbc.queryForObject("SELECT count(*) FROM agents WHERE id = ?", Integer.class, agentId) == 1;
    }

    // --- one agent -----------------------------------------------------------------------

    @Test
    void anAgentWithoutDependenciesIsDeletedByAnAdminUnderItsTag() throws Exception {
        long id = agent("Solo");

        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, "\"999\""))
                .andExpect(status().isPreconditionFailed());
        assertThat(exists(id)).isTrue();

        mockMvc.perform(get("/api/agents/" + id + "/deletion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("AGENT"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.subjects[0].name").value(PREFIX + "Solo"));

        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isNoContent());

        assertThat(exists(id)).isFalse();
        mockMvc.perform(get("/api/agents/" + id)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForList("SELECT detail FROM security_events WHERE type = 'DATA_DELETED'", String.class))
                .singleElement().asString().contains(PREFIX + "Solo");
    }

    @Test
    void anAgentThatDoesNotExistIsA404() throws Exception {
        mockMvc.perform(delete("/api/agents/987654321").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-not-found"));
        mockMvc.perform(get("/api/agents/987654321/deletion")).andExpect(status().isNotFound());
    }

    @Test
    void itsTasksStayUnassignedAndItsRunsKeepItsName() throws Exception {
        long id = agent("Worker");
        long open = task("open", id, "OPEN", null);
        long done = task("done", id, "DONE", null);
        run(done, id, "SUCCEEDED");
        long openVersion = jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, open);

        mockMvc.perform(get("/api/agents/" + id + "/deletion"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.detaches[0]").value(org.hamcrest.Matchers.containsString("1 task da fare")))
                .andExpect(jsonPath("$.detaches[1]").value(org.hamcrest.Matchers.containsString("1 task completate")))
                .andExpect(jsonPath("$.keeps[0]").value(org.hamcrest.Matchers.containsString("1 esecuzioni")));

        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("SELECT agent_id FROM tasks WHERE id IN (?, ?)", Long.class, open, done))
                .containsOnlyNulls().hasSize(2);
        // The task row moved, so its tag moved: a client holding the old one gets a 412, not a silent overwrite.
        assertThat(jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, open)).isEqualTo(openVersion + 1);
        mockMvc.perform(get("/api/tasks/" + done + "/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").doesNotExist())
                .andExpect(jsonPath("$[0].deletedAgentName").value(PREFIX + "Worker"));
    }

    @Test
    void anAgentAssignedInAProjectLeavesTheProjectAndItsTasksInPlace() throws Exception {
        long id = agent("Project Hand");
        long project = jdbc.queryForObject("INSERT INTO projects (name, status, created_at, updated_at) "
                + "VALUES (?, 'ACTIVE', now(), now()) RETURNING id", Long.class, "Del Project " + System.nanoTime());
        long inProject = task("in project", id, "OPEN", project);
        long archivedProject = jdbc.queryForObject("INSERT INTO projects (name, status, created_at, updated_at) "
                + "VALUES (?, 'ARCHIVED', now(), now()) RETURNING id", Long.class, "Del Project archived " + System.nanoTime());
        long frozen = task("frozen", id, "DONE", archivedProject);

        mockMvc.perform(get("/api/agents/" + id + "/deletion"))
                .andExpect(jsonPath("$.detaches[?(@ =~ /.*progetti archiviati.*/)]").exists());

        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT project_id FROM tasks WHERE id = ?", Long.class, inProject)).isEqualTo(project);
        assertThat(jdbc.queryForList("SELECT agent_id FROM tasks WHERE id IN (?, ?)", Long.class, inProject, frozen))
                .containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects WHERE id IN (?, ?)", Integer.class, project,
                archivedProject)).isEqualTo(2);
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE project_id IN (?, ?))",
                project, archivedProject);
        jdbc.update("DELETE FROM tasks WHERE project_id IN (?, ?)", project, archivedProject);
        jdbc.update("DELETE FROM projects WHERE id IN (?, ?)", project, archivedProject);
    }

    @Test
    void itsRoleSkillsAndSoftwareGoWithItWhileTheCatalogAndOtherAgentsOfTheRoleStay() throws Exception {
        long id = agent("Skilled");
        long sameRole = agent("Same Role");
        String skill = jdbc.queryForObject("SELECT key FROM harness_resources ORDER BY id LIMIT 1", String.class);
        String software = jdbc.queryForObject("SELECT key FROM software ORDER BY id LIMIT 1", String.class);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/agents/" + id + "/resources/" + skill)).andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/agents/" + id + "/software/" + software)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/agents/" + id + "/deletion"))
                .andExpect(jsonPath("$.deletes[1]").value(org.hamcrest.Matchers.containsString("1 collegamenti a skill")));

        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_resources WHERE agent_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_software WHERE agent_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM harness_resources WHERE key = ?", Integer.class, skill)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM software WHERE key = ?", Integer.class, software)).isOne();
        assertThat(jdbc.queryForObject("SELECT role FROM agents WHERE id = ?", String.class, sameRole))
                .isEqualTo("Backend Engineer");
    }

    @Test
    void workInProgressBlocksTheDeleteAndSaysWhatToDoFirst() throws Exception {
        long id = agent("Busy");
        long busy = task("in progress", id, "IN_PROGRESS", null);

        mockMvc.perform(get("/api/agents/" + id + "/deletion"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("riassegnala")));
        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(BLOCKED))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Del Task in progress")));
        assertThat(exists(id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT agent_id FROM tasks WHERE id = ?", Long.class, busy)).isEqualTo(id);

        // Reassigning is the way out (ADR-010 D3), and then the delete goes through.
        jdbc.update("UPDATE tasks SET status = 'OPEN' WHERE id = ?", busy);
        long queued = task("queued", id, "OPEN", null);
        run(queued, id, "QUEUED");
        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("esecuzione in corso")));

        jdbc.update("DELETE FROM task_runs WHERE task_id = ?", queued);
        mockMvc.perform(delete("/api/agents/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isNoContent());
    }

    @Test
    void askedExplicitlyTheDeleteStopsWorkInProgressButNeverARunningRun() throws Exception {
        long id = agent("Stopper");
        long busy = task("stopped by the delete", id, "IN_PROGRESS", null);
        long busyVersion = jdbc.queryForObject("SELECT version FROM tasks WHERE id = ?", Long.class, busy);

        mockMvc.perform(get("/api/agents/" + id + "/deletion").param("inProgress", "STOP"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.detaches[0]").value(org.hamcrest.Matchers.containsString("tornano «Da fare»")));

        long other = task("running", id, "OPEN", null);
        run(other, id, "RUNNING");
        mockMvc.perform(delete("/api/agents/" + id).param("inProgress", "STOP").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, busy)).isEqualTo("IN_PROGRESS");

        jdbc.update("DELETE FROM task_runs WHERE task_id = ?", other);
        mockMvc.perform(delete("/api/agents/" + id).param("inProgress", "STOP").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(id)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForMap("SELECT status, agent_id, version FROM tasks WHERE id = ?", busy))
                .containsEntry("status", "OPEN").containsEntry("agent_id", null).containsEntry("version", busyVersion + 1);
    }

    // --- sub-agents ----------------------------------------------------------------------

    @Test
    void subAgentsBecomeTopLevelByDefault() throws Exception {
        long parent = agent("Parent");
        long child = agent("Child");
        parentOf(child, parent);
        long childVersion = jdbc.queryForObject("SELECT version FROM agents WHERE id = ?", Long.class, child);

        mockMvc.perform(get("/api/agents/" + parent + "/deletion"))
                .andExpect(jsonPath("$.subjects.length()").value(1))
                .andExpect(jsonPath("$.detaches[0]").value(org.hamcrest.Matchers.containsString(PREFIX + "Child")));

        mockMvc.perform(delete("/api/agents/" + parent).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag(parent)))
                .andExpect(status().isNoContent());

        assertThat(exists(parent)).isFalse();
        assertThat(exists(child)).isTrue();
        assertThat(jdbc.queryForObject("SELECT parent_id FROM agents WHERE id = ?", Long.class, child)).isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM agents WHERE id = ?", Long.class, child)).isEqualTo(childVersion + 1);
    }

    @Test
    void subAgentsGoTooWhenThatIsAskedAndTheirWorkStillBlocks() throws Exception {
        long parent = agent("Root");
        long child = agent("Branch");
        long grandchild = agent("Leaf");
        parentOf(child, parent);
        parentOf(grandchild, child);
        long busy = task("leaf work", grandchild, "IN_PROGRESS", null);

        mockMvc.perform(get("/api/agents/" + parent + "/deletion").param("subagents", "DELETE"))
                .andExpect(jsonPath("$.subjects.length()").value(3))
                .andExpect(jsonPath("$.allowed").value(false));
        mockMvc.perform(delete("/api/agents/" + parent).param("subagents", "DELETE")
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, tag(parent)))
                .andExpect(status().isConflict());
        assertThat(exists(parent) && exists(child) && exists(grandchild)).isTrue();

        jdbc.update("UPDATE tasks SET status = 'DONE' WHERE id = ?", busy);
        mockMvc.perform(delete("/api/agents/" + parent).param("subagents", "DELETE")
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, tag(parent)))
                .andExpect(status().isNoContent());
        assertThat(exists(parent) || exists(child) || exists(grandchild)).isFalse();
        assertThat(jdbc.queryForObject("SELECT agent_id FROM tasks WHERE id = ?", Long.class, busy)).isNull();
    }

    // --- a selection ---------------------------------------------------------------------

    @Test
    void aSelectionIsDeletedWholeOrNotAtAll() throws Exception {
        long a = agent("Bulk A");
        long b = agent("Bulk B");
        long c = agent("Bulk C");
        long va = jdbc.queryForObject("SELECT version FROM agents WHERE id = ?", Long.class, a);
        long vb = jdbc.queryForObject("SELECT version FROM agents WHERE id = ?", Long.class, b);
        long vc = jdbc.queryForObject("SELECT version FROM agents WHERE id = ?", Long.class, c);
        String body = "{\"agents\":[{\"id\":" + a + ",\"version\":" + va + "},{\"id\":" + b + ",\"version\":" + vb + "}]}";

        mockMvc.perform(post("/api/agents/bulk-delete").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // One stale version: nothing goes.
        mockMvc.perform(post("/api/agents/bulk-delete").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agents\":[{\"id\":" + a + ",\"version\":" + va + "},{\"id\":" + b + ",\"version\":"
                                + (vb + 7) + "}]}"))
                .andExpect(status().isPreconditionFailed());
        // One agent with work in progress: nothing goes.
        long busy = task("bulk busy", c, "IN_PROGRESS", null);
        mockMvc.perform(post("/api/agents/bulk-delete").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agents\":[{\"id\":" + a + ",\"version\":" + va + "},{\"id\":" + c + ",\"version\":"
                                + vc + "}]}"))
                .andExpect(status().isConflict());
        // Repeated or empty selections are malformed.
        mockMvc.perform(post("/api/agents/bulk-delete").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agents\":[{\"id\":" + a + ",\"version\":" + va + "},{\"id\":" + a + ",\"version\":" + va + "}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/agents/bulk-delete").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agents\":[]}"))
                .andExpect(status().isBadRequest());
        assertThat(exists(a) && exists(b) && exists(c)).isTrue();

        mockMvc.perform(get("/api/agents/deletion").param("ids", a + "," + b))
                .andExpect(jsonPath("$.subjects.length()").value(2))
                .andExpect(jsonPath("$.allowed").value(true));
        mockMvc.perform(post("/api/agents/bulk-delete").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        assertThat(exists(a) || exists(b)).isFalse();
        assertThat(exists(c)).isTrue();
        jdbc.update("DELETE FROM task_transitions WHERE task_id = ?", busy);
        jdbc.update("DELETE FROM tasks WHERE id = ?", busy);
    }

    // --- the template set is reinstallable, never re-created on its own ----------------------

    @Test
    void deletedTemplateAgentsStayDeletedUntilAnExplicitInstallWhichNeverDuplicates() throws Exception {
        List<AgentTemplates.Installed> installed = templates.install("software-engineer");
        List<Long> ids = installed.stream().map(AgentTemplates.Installed::agentId).toList();
        assertThat(ids).hasSizeGreaterThan(1);
        try {
            long parent = installed.getFirst().agentId();
            mockMvc.perform(delete("/api/agents/" + parent).param("subagents", "DELETE")
                            .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, tag(parent)))
                    .andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM agents WHERE id IN ("
                    + String.join(",", ids.stream().map(String::valueOf).toList()) + ")", Integer.class)).isZero();

            // What a restart runs: the catalogs and the template-origin pass. Neither brings an agent back.
            templates.recordTemplateOrigins();
            assertThat(count(installed)).isZero();

            mockMvc.perform(get("/api/agent-templates/plan").param("key", "software-engineer"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(installed.size()))
                    .andExpect(jsonPath("$[?(@.outcome == 'EXISTING')]").isEmpty());

            // A partial set: one sub-agent back by hand, then the install creates only what is missing.
            AgentTemplates.Installed someChild = installed.get(1);
            jdbc.update("INSERT INTO agents (name, role, specialization, status, created_at, updated_at) "
                    + "VALUES (?, 'x', 'y', 'ACTIVE', now(), now())", someChild.name());
            mockMvc.perform(get("/api/agent-templates/plan").param("key", "software-engineer"))
                    .andExpect(jsonPath("$[?(@.name == '" + someChild.name() + "')].outcome").value("EXISTING"))
                    .andExpect(jsonPath("$[?(@.outcome == 'CREATED')]", org.hamcrest.Matchers.hasSize(installed.size() - 1)));

            mockMvc.perform(post("/api/agent-templates/software-engineer/install")).andExpect(status().isOk());
            mockMvc.perform(post("/api/agent-templates/software-engineer/install"))
                    .andExpect(jsonPath("$[?(@.outcome == 'CREATED')]").isEmpty());
            for (AgentTemplates.Installed agent : installed) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agents WHERE lower(name) = lower(?)", Integer.class,
                        agent.name())).as(agent.name()).isOne();
            }
        } finally {
            for (AgentTemplates.Installed agent : installed) {
                jdbc.update("UPDATE agents SET parent_id = NULL WHERE lower(name) = lower(?)", agent.name());
            }
            for (AgentTemplates.Installed agent : installed) {
                jdbc.update("DELETE FROM agent_resources WHERE agent_id IN (SELECT id FROM agents WHERE lower(name) = lower(?))", agent.name());
                jdbc.update("DELETE FROM agent_software WHERE agent_id IN (SELECT id FROM agents WHERE lower(name) = lower(?))", agent.name());
                jdbc.update("DELETE FROM agents WHERE lower(name) = lower(?)", agent.name());
            }
        }
    }

    private int count(List<AgentTemplates.Installed> installed) {
        int n = 0;
        for (AgentTemplates.Installed agent : installed) {
            n += jdbc.queryForObject("SELECT count(*) FROM agents WHERE lower(name) = lower(?)", Integer.class, agent.name());
        }
        return n;
    }

    @Test
    void aRunHasAnAgentOrTheNameOfTheOneItHadNeverNeither() throws Exception {
        long id = agent("Constraint");
        long t = task("constraint", id, "DONE", null);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO task_runs (task_id, agent_id, status, system_prompt, user_prompt, correlation_id, "
                        + "requested_by, created_at) VALUES (?, NULL, 'QUEUED', 's', 'u', 'c-none', 'operator', now())", t));
    }
}
