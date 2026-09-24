package com.aicompany.backend.harness;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The agent ecosystem over HTTP (ADR-023). */
class HarnessApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedEngineClient engine;

    private final JsonMapper json = JsonMapper.builder().build();

    /** The agents this class creates (templates included) must not leak into other test classes. */
    @org.junit.jupiter.api.AfterEach
    void removeTheAgentsCreatedHere() {
        noAgents();
    }

    @BeforeEach
    void noAgents() {
        jdbc.update("DELETE FROM task_reviews");
        jdbc.update("DELETE FROM task_handoffs");
        jdbc.update("DELETE FROM task_runs");
        tasks.deleteAll();
        jdbc.update("DELETE FROM agent_resources");
        jdbc.update("DELETE FROM agent_software");
        jdbc.update("UPDATE agents SET parent_id = NULL");
        agents.deleteAll();
    }

    private long agent(String name, String role) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"specialization\":\"x\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    private String tag(long agentId) throws Exception {
        return mockMvc.perform(get("/api/agents/" + agentId + "/profile")).andReturn().getResponse()
                .getHeader(HttpHeaders.ETAG);
    }

    /** PHASE 15: a null list in this response blanked the whole Knowledge Hub. */
    @Test
    void everyTemplateListsItsSubAgentsDirectivesAndResourcesAsArraysNeverNull() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/agent-templates")).andExpect(status().isOk()).andReturn();
        JsonNode templates = json.readTree(result.getResponse().getContentAsString());
        assertThat(templates.size()).isGreaterThan(0);
        for (JsonNode template : templates) {
            for (String list : new String[] {"children", "directives", "resources", "software"}) {
                assertThat(template.path(list).isArray()).as(template.path("key").asString() + "." + list).isTrue();
            }
        }
    }

    @Test
    void theExplorerFindsResourcesByKindAndWords() throws Exception {
        mockMvc.perform(get("/api/resources").param("kind", "MCP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", hasItems("mcp-blender", "mcp-github")))
                .andExpect(jsonPath("$[*].kind", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("MCP"))));
        mockMvc.perform(get("/api/resources").param("q", "react"))
                .andExpect(jsonPath("$[*].key", hasItems("fw-react", "react-typescript")));
        mockMvc.perform(get("/api/resources").param("kind", "TEMPLATE_PROVIDER"))
                .andExpect(jsonPath("$[?(@.key == 'tp-figma-community')].searchUrl",
                        hasItem("https://www.figma.com/community/search?query={q}")));
    }

    @Test
    void theProfileIsPromptEngineeringUnderThePreconditionProtocol() throws Exception {
        long id = agent("Writer", "Tech Writer");

        mockMvc.perform(put("/api/agents/" + id + "/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"systemPrompt\":\"Scrivi chiaro\"}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/agents/" + id + "/profile").header(HttpHeaders.IF_MATCH, tag(id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"Docs\",\"systemPrompt\":\"Scrivi chiaro\",\"directives\":\"Una\\nDue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPrompt").value("Scrivi chiaro"))
                .andExpect(jsonPath("$.directives[1]").value("Due"));
    }

    @Test
    void anAgentCannotBecomeItsOwnAncestor() throws Exception {
        long parent = agent("Software Engineer", "Software Engineer");
        long child = agent("Backend Engineer", "Backend Engineer");
        mockMvc.perform(put("/api/agents/" + child + "/profile").header(HttpHeaders.IF_MATCH, tag(child))
                .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":" + parent + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.parentId").value(parent));

        mockMvc.perform(put("/api/agents/" + parent + "/profile").header(HttpHeaders.IF_MATCH, tag(parent))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":" + child + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-hierarchy-cycle"));
        mockMvc.perform(put("/api/agents/" + parent + "/profile").header(HttpHeaders.IF_MATCH, tag(parent))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":" + parent + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void theHarnessIsSetMembershipIdempotentBothWays() throws Exception {
        long id = agent("Backend", "Backend Engineer");

        mockMvc.perform(put("/api/agents/" + id + "/resources/tdd-workflow")).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/agents/" + id + "/resources/tdd-workflow")).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/agents/" + id + "/software/intellij-junie")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/agents/" + id + "/profile"))
                .andExpect(jsonPath("$.resources.length()").value(1))
                .andExpect(jsonPath("$.software[0].key").value("intellij-junie"));

        mockMvc.perform(delete("/api/agents/" + id + "/resources/tdd-workflow")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/agents/" + id + "/resources/tdd-workflow")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/agents/" + id + "/profile")).andExpect(jsonPath("$.resources.length()").value(0));

        mockMvc.perform(put("/api/agents/" + id + "/resources/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:harness-resource-not-found"));
    }

    @Test
    void templatesInstallAnAgentWithItsSubAgentsAndNeverOverwrite() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent-templates/software-engineer/install"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andReturn();
        JsonNode installed = json.readTree(result.getResponse().getContentAsString());
        long parentId = installed.path(0).path("agentId").asLong();
        assertThat(installed.path(1).path("parentId").asLong()).isEqualTo(parentId);

        mockMvc.perform(get("/api/agent-profiles"))
                .andExpect(jsonPath("$[?(@.name == 'Backend Engineer')].parentId").value(hasItem((int) parentId)))
                .andExpect(jsonPath("$[?(@.name == 'Backend Engineer')].resources[*].key", hasItem("clean-code-java")));

        mockMvc.perform(post("/api/agent-templates/software-engineer/install"))
                .andExpect(jsonPath("$[*].outcome", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("EXISTING"))));
    }

    @Test
    void aProfiledAgentsRunCarriesItsInstructionsAndHarness() throws Exception {
        long id = agent("Writer", "Tech Writer");
        mockMvc.perform(put("/api/agents/" + id + "/profile").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemPrompt\":\"Scrivi chiaro\",\"limits\":\"Niente gergo\"}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/agents/" + id + "/resources/technical-writing")).andExpect(status().isNoContent());
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"README\",\"status\":\"OPEN\",\"priority\":\"LOW\"}")).andReturn();
        long taskId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        String taskTag = mockMvc.perform(put("/api/tasks/" + taskId + "/agent")
                        .header(HttpHeaders.IF_MATCH, created.getResponse().getHeader(HttpHeaders.ETAG))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + id + "}"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, taskTag)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());

        for (int i = 0; i < 100 && engine.requests().isEmpty(); i++) {
            Thread.sleep(50);
        }
        EngineClient.Request request = engine.requests().getLast();
        for (int i = 0; i < 100; i++) {
            String runs = mockMvc.perform(get("/api/tasks/" + taskId + "/runs")).andReturn().getResponse()
                    .getContentAsString();
            if (runs.contains("SUCCEEDED") || runs.contains("FAILED")) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(request.system()).contains("Agent instructions:\nScrivi chiaro").contains("Limits:\nNiente gergo")
                .contains("skill Documentazione tecnica");
    }

    @Test
    void aProjectAdoptsFrameworksFromTheExplorer() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Explorer " + System.nanoTime() + "\"}")).andReturn();
        long projectId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();

        mockMvc.perform(put("/api/projects/" + projectId + "/resources/fw-react")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/projects/" + projectId + "/resources"))
                .andExpect(jsonPath("$[0].key").value("fw-react"));
        mockMvc.perform(delete("/api/projects/" + projectId + "/resources/fw-react")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/projects/" + projectId + "/resources")).andExpect(jsonPath("$.length()").value(0));
        jdbc.update("DELETE FROM project_resources WHERE project_id = ?", projectId);
    }
}
