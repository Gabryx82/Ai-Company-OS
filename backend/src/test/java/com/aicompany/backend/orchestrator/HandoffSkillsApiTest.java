package com.aicompany.backend.orchestrator;

import com.aicompany.backend.harness.hub.SkillLocations;
import com.aicompany.backend.harness.library.SkillLibrary;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 35 (ADR-041, phase 2; revises ADR-026 §3): a tool that loads skills by itself
 * from its own folder is not asked to read the library's files. The package names each
 * skill and says to use it if pertinent -- after checking, through the Knowledge Hub,
 * that the Hub's copy is in that tool's folder. A copy that is missing, behind the
 * library or not the Hub's is said as such, with the copy proposed and the library file
 * to read meanwhile; nothing is written there by the handoff. Knowledge, which has no
 * folder of its own in the apps, is given by path, and only when pertinent. A tool that
 * does not load skills gets the package as before.
 */
class HandoffSkillsApiTest extends AbstractPostgresTest {

    private static final List<String> KEYS = List.of("zeta-copied", "zeta-missing", "zeta-foreign",
            "zeta-sql-notes", "zeta-react-notes");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillLibrary library;

    @Autowired
    private SkillLocations locations;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private Path claudeSkills;
    private long agentId;
    private long projectId;

    private static String entry(String key, String kind, String name, String description, String body) {
        return "---\nkey: " + key + "\nname: " + name + "\nkind: " + kind + "\ndescription: " + description
                + "\n---\n\n# " + name + "\n\n" + body + "\n";
    }

    @BeforeEach
    void anAgentWhoseSkillsStandDifferentlyInClaudeCode() throws Exception {
        clean();
        claudeSkills = locations.home().resolve(".claude").resolve("skills");
        Files.createDirectories(claudeSkills);
        for (String content : List.of(
                entry("zeta-copied", "SKILL", "Vincoli SQL", "Ogni vincolo ha un nome.", "- Vincoli con nome."),
                entry("zeta-missing", "SKILL", "Migrazioni additive", "Mai una migrazione distruttiva.", "- Solo aggiunte."),
                entry("zeta-foreign", "SKILL", "Indici", "Ogni query ha il suo indice.", "- Indici giustificati."),
                entry("zeta-sql-notes", "KNOWLEDGE", "Note SQL dei prestiti", "Il modello dei prestiti in SQL.", "- I prestiti."),
                entry("zeta-react-notes", "KNOWLEDGE", "Note React", "Componenti della console.", "- I componenti."))) {
            mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(json.createObjectNode().put("content", content))))
                    .andExpect(status().isCreated());
        }
        // In Claude Code: the Hub's copy of one skill; a folder of the operator's own under the name of another.
        mockMvc.perform(post("/api/skill-hub/library/zeta-copied/copies/claude-code")).andExpect(status().isOk());
        Files.createDirectories(claudeSkills.resolve("zeta-foreign"));
        Files.writeString(claudeSkills.resolve("zeta-foreign/SKILL.md"), "---\nname: zeta-foreign\ndescription: mia\n---\n\nMia.\n");

        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P35 Skills Bot\",\"role\":\"Database Engineer\",\"specialization\":\"SQL\"}")).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        for (String key : KEYS) {
            mockMvc.perform(put("/api/agents/" + agentId + "/resources/" + key)).andExpect(status().isNoContent());
        }
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Skill P35\"}")).andReturn();
        projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"DELEGATED\",\"workspacePath\":\""
                        + folder.resolve("skill").toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
    }

    @AfterEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P35 %'");
        jdbc.update("DELETE FROM projects WHERE name = 'Skill P35'");
        jdbc.update("DELETE FROM agent_resources WHERE agent_id IN (SELECT id FROM agents WHERE name = 'P35 Skills Bot')");
        jdbc.update("DELETE FROM agents WHERE name = 'P35 Skills Bot'");
        for (String key : KEYS) {
            jdbc.update("DELETE FROM skill_installations WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", key);
            jdbc.update("DELETE FROM harness_resources WHERE key = ?", key);
            delete(library.root().resolve("skills").resolve(key));
            delete(library.root().resolve("knowledge").resolve(key + ".md"));
            delete(locations.home().resolve(".claude").resolve("skills").resolve(key));
        }
    }

    @Test
    void claudeCodeIsToldToUseTheSkillsItLoadsAndWhatIsMissingIsSaidAndProposed() throws Exception {
        long taskId = task("P35 Migrazione SQL dei prestiti");
        String pkg = handoff(taskId, "claude-code");
        String skills = pkg.substring(pkg.indexOf("## Skill, knowledge, tool e MCP"), pkg.indexOf("## Contesto da leggere"));

        // The Hub's copy is in Claude Code's folder: used by name, not read from the library.
        assertThat(line(skills, "Vincoli SQL")).contains("usa la skill `zeta-copied` se pertinente")
                .contains(claudeSkills.resolve("zeta-copied").toString());
        assertThat(skills).doesNotContain(libraryFile("skills/zeta-copied/SKILL.md"));
        // Not there: said, the copy proposed, the library file to read meanwhile if pertinent.
        assertThat(line(skills, "Migrazioni additive")).contains("non è nella cartella di Claude Code")
                .contains("Knowledge Hub").contains(libraryFile("skills/zeta-missing/SKILL.md"));
        // A folder of the operator's own under that name: not the Hub's, not touched.
        assertThat(line(skills, "Indici")).contains("non viene dal Knowledge Hub")
                .contains(libraryFile("skills/zeta-foreign/SKILL.md"));
        // Knowledge by path: the pertinent one to read; PHASE 36 -- the other named, to read only if the task needs it.
        assertThat(line(skills, "Note SQL dei prestiti")).contains(libraryFile("knowledge/zeta-sql-notes.md"))
                .contains("leggilo e applicalo");
        assertThat(line(skills, "Note React")).contains("consultala solo se la task la richiede")
                .contains(libraryFile("knowledge/zeta-react-notes.md"));

        // What the package asks to read follows: no library skill is required reading any more.
        List<String> reads = reads(taskId);
        assertThat(reads).contains("SKILL " + libraryFile("skills/zeta-missing/SKILL.md") + " false",
                        "SKILL " + libraryFile("skills/zeta-foreign/SKILL.md") + " false",
                        "KNOWLEDGE " + libraryFile("knowledge/zeta-sql-notes.md") + " true",
                        "KNOWLEDGE " + libraryFile("knowledge/zeta-react-notes.md") + " false")
                .noneMatch(r -> r.contains("zeta-copied"));

        // The handoff wrote nothing in Claude Code's folder.
        assertThat(claudeSkills.resolve("zeta-missing")).doesNotExist();
        assertThat(claudeSkills.resolve("zeta-foreign/.aicos-hub.json")).doesNotExist();
    }

    @Test
    void aCopyBehindTheLibraryIsSaidAndTheLibraryFileGivenMeanwhile() throws Exception {
        long version = json.readTree(mockMvc.perform(get("/api/resources/zeta-copied/file")).andReturn().getResponse()
                .getContentAsString()).path("version").asLong();
        mockMvc.perform(put("/api/resources/zeta-copied/file").header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(json.createObjectNode().put("content",
                        entry("zeta-copied", "SKILL", "Vincoli SQL", "Ogni vincolo ha un nome.", "- Vincoli con nome, sempre.")))))
                .andExpect(status().isOk());

        String pkg = handoff(task("P35 Vincoli SQL"), "claude-code");
        assertThat(line(pkg, "Vincoli SQL")).contains("non è allineata alla libreria")
                .contains(libraryFile("skills/zeta-copied/SKILL.md"));
    }

    @Test
    void aToolThatDoesNotLoadSkillsGetsThePackageAsBefore() throws Exception {
        String pkg = handoff(task("P35 Migrazione SQL dei prestiti"), "manual");
        for (String file : List.of("skills/zeta-copied/SKILL.md", "skills/zeta-missing/SKILL.md", "skills/zeta-foreign/SKILL.md",
                "knowledge/zeta-sql-notes.md", "knowledge/zeta-react-notes.md")) {
            assertThat(pkg).contains("istruzioni nel file `" + libraryFile(file) + "`: leggilo e applicalo");
        }
        assertThat(pkg).doesNotContain("usa la skill");
    }

    // ------------------------------------------------------------------ helpers

    private String libraryFile(String relative) {
        return library.root().resolve(relative).toString();
    }

    /** The line of the skill section that names a resource, with its instruction below it. */
    private static String line(String text, String name) {
        int at = text.indexOf("** " + name + " —");
        assertThat(at).as("the package names " + name).isNotNegative();
        int next = text.indexOf("\n- **", at);
        return text.substring(at, next < 0 ? text.length() : next);
    }

    private long task(String title) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"HIGH\",\"projectId\":" + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private String handoff(long taskId, String target) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"" + target + "\"}"))
                .andExpect(status().isAccepted());
        return Files.readString(folder.resolve("skill/.aicos/handoffs/TASK-" + taskId + "-" + target + ".md"));
    }

    private List<String> reads(long taskId) throws Exception {
        List<String> reads = new ArrayList<>();
        for (JsonNode h : json.readTree(mockMvc.perform(get("/api/usage/measurements")).andReturn().getResponse()
                .getContentAsString()).path("handoffs")) {
            if (h.path("taskId").asLong() == taskId) {
                h.path("reads").forEach(r -> reads.add(r.path("kind").asString() + " " + r.path("path").asString() + " "
                        + r.path("required").asBoolean()));
            }
        }
        return reads;
    }

    private static void delete(Path path) throws Exception {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
