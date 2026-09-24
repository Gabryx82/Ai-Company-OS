package com.aicompany.backend.deletion;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 20 (ADR-027): deleting for real, distinct from archiving -- an admin's
 * decision, confirmed by typing the name, under the row's tag, never while a
 * run is in flight, with every relation handled and the files left on disk.
 */
class DeletionApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private String admin;

    @BeforeEach
    void anAdminSession() throws Exception {
        jdbc.update("UPDATE app_users SET failed_attempts = 0, locked_until = NULL");
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-root-secret-0123\"}")).andExpect(status().isOk()).andReturn();
        admin = "Bearer " + json.readTree(login.getResponse().getContentAsString()).path("token").asString();
        jdbc.update("DELETE FROM security_events");
    }

    @org.junit.jupiter.api.AfterEach
    void removeTheFixtureAgents() {
        jdbc.update("DELETE FROM task_runs WHERE agent_id IN (SELECT id FROM agents WHERE name LIKE 'Del Bot %')");
        jdbc.update("DELETE FROM agents WHERE name LIKE 'Del Bot %'");
    }

    private long project(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated()).andReturn();
        return Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
    }

    private long task(String title, Long projectId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"LOW\""
                        + (projectId == null ? "" : ",\"projectId\":" + projectId) + "}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    private String tag(String path) throws Exception {
        return mockMvc.perform(get(path)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private void aRun(long taskId, String status) {
        Long agent = jdbc.queryForObject("INSERT INTO agents (name, role, specialization, status, created_at, updated_at) "
                + "VALUES ('Del Bot ' || floor(random()*1000000), 'r', 's', 'ACTIVE', now(), now()) RETURNING id", Long.class);
        boolean done = status.equals("SUCCEEDED");
        jdbc.update("INSERT INTO task_runs (task_id, agent_id, status, system_prompt, user_prompt, correlation_id, requested_by, "
                + "created_at, output, finish_reason, finished_at) VALUES (?, ?, ?, 's', 'u', 'c-' || floor(random()*1000000), "
                + "'operator', now(), ?, ?, ?)", taskId, agent, status, done ? "ok" : null, done ? "stop" : null,
                done ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    @Test
    void anOperatorCannotDeleteAndAnAdminMustTypeTheNameAndHoldTheTag() throws Exception {
        long id = task("Scrivere il README", null);

        mockMvc.perform(delete("/api/tasks/" + id).param("confirm", "Scrivere il README").header(HttpHeaders.IF_MATCH, tag("/api/tasks/" + id)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/tasks/" + id).header(HttpHeaders.AUTHORIZATION, admin).param("confirm", "Scrivere il README"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(delete("/api/tasks/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag("/api/tasks/" + id)).param("confirm", "sì, elimina"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:delete-confirmation-mismatch"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE id = ?", Integer.class, id)).isOne();

        mockMvc.perform(delete("/api/tasks/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag("/api/tasks/" + id)).param("confirm", "  scrivere il readme "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Scrivere il README"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForList("SELECT detail FROM security_events WHERE type = 'DATA_DELETED'", String.class))
                .singleElement().asString().contains("Scrivere il README");
    }

    @Test
    void aTaskGoesWithItsRunsHandoffsAndReviewsButItsDailyItemsStayAsPersonalOnes() throws Exception {
        long id = task("Migrare lo schema", null);
        aRun(id, "SUCCEEDED");
        jdbc.update("INSERT INTO daily_items (day, task_id, status, priority, position, created_at, updated_at, version) "
                + "VALUES (current_date, ?, 'TODO', 'MEDIUM', 0, now(), now(), 0)", id);

        mockMvc.perform(get("/api/tasks/" + id + "/deletion"))
                .andExpect(jsonPath("$.runs").value(1))
                .andExpect(jsonPath("$.dailyItems").value(1));

        mockMvc.perform(delete("/api/tasks/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag("/api/tasks/" + id)).param("confirm", "Migrare lo schema"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_runs WHERE task_id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM daily_items WHERE task_id IS NULL AND title LIKE 'Migrare%'", String.class))
                .isEqualTo("Migrare lo schema (task eliminata)");
    }

    @Test
    void nothingIsDeletedWhileARunIsInFlight() throws Exception {
        long id = task("In esecuzione", null);
        aRun(id, "RUNNING");
        mockMvc.perform(delete("/api/tasks/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag("/api/tasks/" + id)).param("confirm", "In esecuzione"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:task-run-in-progress"));
        jdbc.update("UPDATE task_runs SET status = 'FAILED', failure_type = 'test', finished_at = now() WHERE task_id = ?", id);
    }

    @Test
    void aProjectKeepsOrDeletesItsTasksAsTheAdminSaysAndThereIsNoDefault() throws Exception {
        long kept = project("Progetto da chiudere");
        long keptTask = task("Task che resta", kept);
        long gone = project("Progetto da cancellare");
        long goneTask = task("Task che sparisce", gone);

        mockMvc.perform(delete("/api/projects/" + kept).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag("/api/projects/" + kept)).param("confirm", "Progetto da chiudere"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/projects/" + kept + "/deletion").param("tasks", "DETACH"))
                .andExpect(jsonPath("$.tasks").value(1))
                .andExpect(jsonPath("$.notes[0]").value(org.hamcrest.Matchers.containsString("restano")));

        mockMvc.perform(delete("/api/projects/" + kept).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag("/api/projects/" + kept))
                        .param("tasks", "DETACH").param("confirm", "Progetto da chiudere"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT project_id IS NULL FROM tasks WHERE id = ?", Boolean.class, keptTask)).isTrue();
        mockMvc.perform(get("/api/projects/" + kept)).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/projects/" + gone).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tag("/api/projects/" + gone))
                        .param("tasks", "DELETE").param("confirm", "Progetto da cancellare"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE id = ?", Integer.class, goneTask)).isZero();
    }

    @Test
    void archivingStaysTheReversibleWayAndIsNotADelete() throws Exception {
        long id = project("Solo archiviato");
        mockMvc.perform(post("/api/projects/" + id + "/archive").header(HttpHeaders.IF_MATCH, tag("/api/projects/" + id)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + id + "/restore").header(HttpHeaders.IF_MATCH, tag("/api/projects/" + id)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/projects/" + id)).andExpect(status().isOk());
    }
}
