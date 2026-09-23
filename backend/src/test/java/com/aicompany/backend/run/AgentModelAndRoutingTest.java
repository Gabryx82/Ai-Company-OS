package com.aicompany.backend.run;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-020: an agent says which model it runs on, and the registry -- not four
 * hard-coded names -- answers "who fits this work" (TD-08).
 */
class AgentModelAndRoutingTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedEngineClient engine;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearEverything() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        agentRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // The agent's model
    // ------------------------------------------------------------------

    @Test
    void anAgentCanBeCreatedWithAModelAndSaysSo() throws Exception {

        mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Local Coder","role":"Engineer","specialization":"Java","model":"ollama:llama3.2:3b"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.model").value("ollama:llama3.2:3b"));
    }

    @Test
    void anAgentWithoutAModelUsesTheEnginesDefaultAndSaysNothing() throws Exception {

        mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Plain","role":"Engineer","specialization":"Java"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.model").doesNotExist());
    }

    @Test
    void aModelThatIsNotAModelIdIsRefused() throws Exception {

        mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Bad","role":"Engineer","specialization":"Java","model":"Llama 3!"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.model").exists());
    }

    /** A PUT replaces: the model is one of the details, set and cleared like them. */
    @Test
    void theModelIsADetailThatAPutReplaces() throws Exception {

        Long id = agentRepository.saveAndFlush(new Agent("Coder", "Engineer", "Java", "ollama:qwen3.5:9b")).getId();

        mockMvc.perform(put("/api/agents/{id}", id).header(HttpHeaders.IF_MATCH, etag("/api/agents/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Coder","role":"Engineer","specialization":"Java","model":"anthropic:claude-opus-5"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("anthropic:claude-opus-5"));

        mockMvc.perform(put("/api/agents/{id}", id).header(HttpHeaders.IF_MATCH, etag("/api/agents/" + id))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Coder","role":"Engineer","specialization":"Java"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").doesNotExist());
    }

    @Test
    void aRunUsesTheAgentsModelUnlessTheLaunchAsksForAnother() throws Exception {

        Long agent = agentRepository.saveAndFlush(new Agent("Coder", "Engineer", "Java", "ollama:qwen3.5:9b")).getId();
        Long taskId = task("Write the client", agent);

        long first = runId(mockMvc.perform(post("/api/tasks/{id}/runs", taskId)
                        .header(HttpHeaders.IF_MATCH, etag("/api/tasks/" + taskId)))
                .andExpect(jsonPath("$.requestedModel").value("ollama:qwen3.5:9b")).andReturn()
                .getResponse().getContentAsString());
        awaitFinished(first);
        assertThat(engine.requests().getLast().model()).isEqualTo("ollama:qwen3.5:9b");

        long second = runId(mockMvc.perform(post("/api/tasks/{id}/runs", taskId)
                        .header(HttpHeaders.IF_MATCH, etag("/api/tasks/" + taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"echo:default\"}"))
                .andExpect(jsonPath("$.requestedModel").value("echo:default")).andReturn()
                .getResponse().getContentAsString());
        awaitFinished(second);
        assertThat(engine.requests().getLast().model()).isEqualTo("echo:default");
    }

    // ------------------------------------------------------------------
    // Routing: TD-08
    // ------------------------------------------------------------------

    @Test
    void suggestionsRankTheRegistrysAgentsByWhatTheWorkIsAbout() throws Exception {

        Long database = agentRepository.saveAndFlush(
                new Agent("Database Specialist", "Database Engineer", "PostgreSQL and data modeling")).getId();
        Long frontend = agentRepository.saveAndFlush(
                new Agent("Frontend Developer", "Frontend Engineer", "React TypeScript UI development")).getId();
        Long architect = agentRepository.saveAndFlush(
                new Agent("Code Architect", "Software Engineer", "Backend architecture and system design")).getId();

        mockMvc.perform(post("/api/routing/suggestions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"text":"Add an index to the PostgreSQL database for the runs table"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].agentId").value(database))
                .andExpect(jsonPath("$[0].matchedTerms", contains("database", "postgresql")))
                .andExpect(jsonPath("$[0].score").value(2));

        mockMvc.perform(post("/api/routing/suggestions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"text":"Build the React dashboard in TypeScript"}"""))
                .andExpect(jsonPath("$[0].agentId").value(frontend));

        mockMvc.perform(post("/api/routing/suggestions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"text":"Design the backend architecture of the planner"}"""))
                .andExpect(jsonPath("$[0].agentId").value(architect));
    }

    @Test
    void anInactiveAgentIsNeverSuggested() throws Exception {

        Agent retired = new Agent("Retired DBA", "Database Engineer", "PostgreSQL");
        retired.deactivate();
        agentRepository.saveAndFlush(retired);
        Long active = agentRepository.saveAndFlush(new Agent("Generalist", "Engineer", "anything")).getId();

        mockMvc.perform(post("/api/routing/suggestions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"text":"PostgreSQL database work"}"""))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].agentId").value(active))
                .andExpect(jsonPath("$[0].score").value(0));
    }

    @Test
    void aTaskCanAskWhoFitsIt() throws Exception {

        agentRepository.saveAndFlush(new Agent("Frontend Developer", "Frontend Engineer", "React TypeScript UI"));
        Long database = agentRepository.saveAndFlush(
                new Agent("Database Specialist", "Database Engineer", "PostgreSQL and data modeling")).getId();
        Long taskId = taskRepository.saveAndFlush(new Task("Model the schema",
                "Data modeling for the PostgreSQL tables", TaskStatus.OPEN, TaskPriority.HIGH)).getId();

        mockMvc.perform(get("/api/tasks/{id}/agent-suggestions", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(database));

        mockMvc.perform(get("/api/tasks/{id}/agent-suggestions", 987654321L))
                .andExpect(status().isNotFound());
    }

    @Test
    void suggestingWritesNothing() throws Exception {

        Long agent = agentRepository.saveAndFlush(new Agent("Coder", "Engineer", "Java")).getId();
        Long taskId = task("Java work", null);
        String before = etag("/api/tasks/" + taskId);

        mockMvc.perform(get("/api/tasks/{id}/agent-suggestions", taskId)).andExpect(status().isOk());

        assertThat(etag("/api/tasks/" + taskId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT agent_id FROM tasks WHERE id = ?", Long.class, taskId)).isNull();
        assertThat(agent).isNotNull();
    }

    @Test
    void emptyTextIsAValidationFailure() throws Exception {

        mockMvc.perform(post("/api/routing/suggestions").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());
    }

    /** The placeholder is gone, and nothing answers where it was. */
    @Test
    void theOldOrchestratorEndpointNoLongerExists() throws Exception {

        mockMvc.perform(post("/api/orchestrator").contentType(MediaType.APPLICATION_JSON).content("ecommerce"))
                .andExpect(status().isNotFound());
    }

    // --- helpers -----------------------------------------------------------

    private Long task(String title, Long agentId) {
        Long id = taskRepository.saveAndFlush(new Task(title, null, TaskStatus.OPEN, TaskPriority.HIGH)).getId();
        jdbc.update("UPDATE tasks SET agent_id = ? WHERE id = ?", agentId, id);
        return id;
    }

    private String etag(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private long runId(String body) {
        return json.readTree(body).get("id").asLong();
    }

    private void awaitFinished(long runId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String status = json.readTree(mockMvc.perform(get("/api/runs/{id}", runId)).andReturn()
                    .getResponse().getContentAsString()).get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("run did not finish");
    }
}
