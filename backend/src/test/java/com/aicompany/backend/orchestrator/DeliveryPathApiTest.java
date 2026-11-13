package com.aicompany.backend.orchestrator;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 36 (ADR-042 §5): the prompt shown for a task leads to its package, and a project
 * begun before -- its AGENTS.md, its context map and its task documents still with the
 * text AI Company OS wrote then -- follows the way tasks are delivered now from its first
 * handoff: what is still verbatim is brought up to date, with a copy of the file as it
 * was; what the operator wrote stays.
 */
class DeliveryPathApiTest extends AbstractPostgresTest {

    private static final String OLD_STEPS = """
            ## Come si lavora su una task

            1. Leggi `tasks/TASK-NNN.md`, poi la sua fase, poi solo i file che la task elenca.
            2. Resta nello scope della task. Quello che noti e non fai, scrivilo nella sezione *Note* della task.
            3. Scrivi i test richiesti dalla task e falli girare.
            4. Alla fine aggiorna la sezione *Esito* della task: cosa hai fatto, file toccati, test, cosa resta.
            5. Non dichiarare finita una task: la chiude l'operatore dopo la review (Human-in-the-Loop).

            ## Livello di autonomia: GUIDED
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;
    private long projectId;
    private Path workspace;

    @BeforeEach
    void aProjectBegunBeforeThisPhase() throws Exception {
        clean();
        workspace = folder.resolve("vecchio");
        Files.createDirectories(workspace.resolve("tasks"));
        Files.createDirectories(workspace.resolve(".aicos"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# Vecchio — governance per gli agenti\n\n" + OLD_STEPS);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# Le mie regole per Claude\n\nNiente emoji.\n");
        Files.writeString(workspace.resolve(".aicos/CONTEXT_MAP.md"), "# Mappa\n\nPrompt tipo, che basta a un agente per "
                + "partire:\n\n> Esegui TASK-NNN seguendo `tasks/TASK-NNN.md` e la governance in `AGENTS.md`.\n");
        Files.writeString(workspace.resolve("tasks/TASK-900.md"), "# TASK-900 — Una task del piano\n\n## Prompt compatto\n\n"
                + "> Esegui TASK-900 seguendo `tasks/TASK-900.md` e la governance in `AGENTS.md`.\n\n## Esito\n\nFatto.\n");
        Files.writeString(workspace.resolve("tasks/TASK-901.md"), "# TASK-901 — Riscritta dall'operatore\n\n"
                + "## Prompt compatto\n\n> Esegui TASK-901 come dico io.\n");

        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Path Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"SQL\"}"))
                .andExpect(status().isCreated()).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Vecchio P36\"}")).andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"GUIDED\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P36P %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P36P %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P36P %'");
        jdbc.update("DELETE FROM projects WHERE name = 'Vecchio P36'");
        jdbc.update("DELETE FROM agents WHERE name = 'Path Bot'");
    }

    private long task() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"P36P Una task\",\"status\":\"OPEN\",\"priority\":\"HIGH\",\"projectId\":"
                                + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    @Test
    void thePromptShownForATaskLeadsToItsPackage() throws Exception {
        long taskId = task();
        JsonNode decision = json.readTree(mockMvc.perform(get("/api/tasks/" + taskId + "/orchestration"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(decision.path("prompt").asString())
                .contains("partendo dal suo pacchetto di consegna, il più recente `.aicos/handoffs/TASK-" + taskId + "-*.md`")
                .contains("se non c'è, chiedi all'operatore di consegnare la task");
    }

    @Test
    void theFirstHandoffBringsWhatAiCompanyOsWroteUpToDateAndLeavesTheOperatorsOwn() throws Exception {
        long taskId = task();
        JsonNode result = json.readTree(mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs")
                        .header(HttpHeaders.IF_MATCH, tag(taskId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());

        assertThat(result.path("written").toString()).contains("AGENTS.md").contains(".aicos/CONTEXT_MAP.md")
                .contains("tasks/TASK-900.md").doesNotContain("tasks/TASK-901.md");
        assertThat(Files.readString(workspace.resolve("AGENTS.md")))
                .contains("1. Parti dal pacchetto della tua task in `.aicos/handoffs/`")
                .startsWith("# Vecchio — governance per gli agenti");
        assertThat(Files.readString(workspace.resolve(".aicos/CONTEXT_MAP.md")))
                .contains("> Leggi .aicos/handoffs/TASK-NNN-<strumento>.md ed esegui la task che descrive.");
        assertThat(Files.readString(workspace.resolve("tasks/TASK-900.md"))).doesNotContain("## Prompt compatto")
                .contains("> Esegui TASK-900 partendo dal suo pacchetto di consegna").endsWith("## Esito\n\nFatto.\n");
        assertThat(Files.readString(workspace.resolve("tasks/TASK-901.md"))).as("the operator's own prompt stays")
                .contains("> Esegui TASK-901 come dico io.");
        assertThat(Files.readString(workspace.resolve("CLAUDE.md"))).isEqualTo("# Le mie regole per Claude\n\nNiente emoji.\n");

        // Each file changed is kept as it was.
        Path upgrades = workspace.resolve(".aicos/upgrades");
        try (Stream<Path> stamps = Files.list(upgrades)) {
            Path stamp = stamps.findFirst().orElseThrow();
            assertThat(Files.readString(stamp.resolve("AGENTS.md"))).contains(OLD_STEPS);
            assertThat(Files.readString(stamp.resolve("tasks/TASK-900.md"))).contains("## Prompt compatto");
        }

        // A second handoff finds nothing more to bring up to date.
        JsonNode again = json.readTree(mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs")
                        .header(HttpHeaders.IF_MATCH, tag(taskId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(again.path("written").toString()).doesNotContain("AGENTS.md").doesNotContain("TASK-900.md");
    }
}
