package com.aicompany.backend.harness;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeRemoteText;
import com.aicompany.backend.support.ScriptedEngineClient;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PHASE 19 (ADR-026): skills and knowledge as files, edited by hand or from the console, and imported. */
class LibraryApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillLibrary library;

    @Autowired
    private FakeRemoteText web;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    @AfterEach
    void aCleanLibrary() throws Exception {
        web.reset();
        jdbc.update("DELETE FROM agent_resources");
        jdbc.update("DELETE FROM harness_resources WHERE origin <> 'CATALOG'");
        jdbc.update("UPDATE harness_resources SET file_path = NULL, file_synced_at = NULL");
        // The catalog entry this class edits goes back to its catalog name.
        jdbc.update("UPDATE harness_resources SET name = 'Clean code Java / Spring' WHERE key = 'clean-code-java'");
        jdbc.update("DELETE FROM task_runs WHERE agent_id IN (SELECT id FROM agents WHERE name = 'Skilled Bot')");
        jdbc.update("UPDATE tasks SET agent_id = NULL WHERE agent_id IN (SELECT id FROM agents WHERE name = 'Skilled Bot')");
        jdbc.update("DELETE FROM agents WHERE name = 'Skilled Bot'");
        if (Files.exists(library.root())) {
            try (Stream<Path> walk = Files.walk(library.root())) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static final String NEW_SKILL = """
            ---
            key: api-review
            name: Revisione delle API REST
            kind: SKILL
            description: Controlla verbi, codici di stato e problem detail.
            tags: [api, rest]
            ---

            # Revisione delle API REST

            - Ogni errore è un problem detail con un type stabile.
            - Mai 200 per una creazione: 201 con Location.
            """;

    private JsonNode file(String key) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/resources/" + key + "/file")).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void aCatalogSkillBecomesAFileTheOperatorEditsAndTheIndexFollowsTheFile() throws Exception {
        JsonNode before = file("clean-code-java");
        assertThat(before.path("exists").asBoolean()).isFalse();
        assertThat(before.path("relativePath").asString()).isEqualTo("skills/clean-code-java/SKILL.md");

        mockMvc.perform(post("/api/resources/clean-code-java/file")).andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.startsWith("---\nkey: clean-code-java\n")));
        Path onDisk = library.root().resolve("skills/clean-code-java/SKILL.md");
        assertThat(onDisk).exists();

        // Edited by hand in an editor: the console reads what is on disk.
        String edited = Files.readString(onDisk).replace("name: Clean code Java / Spring", "name: Clean code Java 21");
        Files.writeString(onDisk, edited);
        assertThat(file("clean-code-java").path("content").asString()).contains("Clean code Java 21");

        // Edited from the console: validated, written, re-indexed, under the resource's tag.
        JsonNode current = file("clean-code-java");
        String fromConsole = current.path("content").asString()
                .replace("name: Clean code Java 21", "name: Clean code per Java 21 e Spring Boot 4");
        ObjectNode body = json.createObjectNode().put("content", fromConsole);
        mockMvc.perform(put("/api/resources/clean-code-java/file").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/resources/clean-code-java/file").header(HttpHeaders.IF_MATCH, "\"" + current.path("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/resources").param("kind", "SKILL"))
                .andExpect(jsonPath("$[?(@.key == 'clean-code-java')].name").value(hasItem("Clean code per Java 21 e Spring Boot 4")))
                .andExpect(jsonPath("$[?(@.key == 'clean-code-java')].filePath").value(hasItem("skills/clean-code-java/SKILL.md")));

        // The key and the kind of a file cannot be changed from inside it.
        JsonNode again = file("clean-code-java");
        String renamed = again.path("content").asString().replace("key: clean-code-java", "key: something-else");
        mockMvc.perform(put("/api/resources/clean-code-java/file").header(HttpHeaders.IF_MATCH, "\"" + again.path("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(json.createObjectNode().put("content", renamed))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-document-invalid"));
    }

    @Test
    void aNewSkillIsAFileInItsOwnFolderAndAnMcpEntryHasNoFile() throws Exception {
        mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("content", NEW_SKILL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("USER"))
                .andExpect(jsonPath("$.filePath").value("skills/api-review/SKILL.md"));
        assertThat(Files.readString(library.root().resolve("skills/api-review/SKILL.md"))).contains("Mai 200 per una creazione");

        mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("content", NEW_SKILL))))
                .andExpect(status().isConflict());

        String mcp = jdbc.queryForObject("SELECT key FROM harness_resources WHERE kind = 'MCP' LIMIT 1", String.class);
        mockMvc.perform(get("/api/resources/" + mcp + "/file"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:resource-not-file-backed"));
    }

    @Test
    void filesWrittenInTheFolderAreIndexedAndABrokenOneIsReportedNotGuessed() throws Exception {
        Path skill = library.root().resolve("skills/api-review/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, NEW_SKILL);
        Path broken = library.root().resolve("knowledge/appunti.md");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "# Appunti senza frontmatter\n");

        mockMvc.perform(post("/api/library/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(hasItem("skills/api-review/SKILL.md")))
                .andExpect(jsonPath("$.invalid[0]").value(org.hamcrest.Matchers.startsWith("knowledge/appunti.md")));
        mockMvc.perform(get("/api/resources").param("q", "Revisione delle API"))
                .andExpect(jsonPath("$[0].origin").value("FILE"));
    }

    @Test
    void anImportTakesARawMarkdownFromAPublicHttpsUrlAndNothingElse() throws Exception {
        web.answer("text/plain; charset=utf-8", "# Accessibilità dei form\n\nOgni input ha un'etichetta.\n");
        mockMvc.perform(post("/api/library/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://github.com/acme/skills/blob/main/form-a11y.md\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("form-a11y"))
                .andExpect(jsonPath("$.origin").value("WEB"))
                .andExpect(jsonPath("$.sourceUrl").value("https://raw.githubusercontent.com/acme/skills/main/form-a11y.md"));
        assertThat(web.fetched()).extracting(Object::toString)
                .containsExactly("https://raw.githubusercontent.com/acme/skills/main/form-a11y.md");

        for (String refused : new String[] {"http://example.org/skill.md", "https://localhost/skill.md",
                "https://127.0.0.1/skill.md", "https://10.0.0.8/skill.md", "file:///etc/passwd"}) {
            mockMvc.perform(post("/api/library/import").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + refused + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-import-refused"));
        }
        web.answer("text/html", "<html><body>not a skill</body></html>");
        mockMvc.perform(post("/api/library/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://raw.githubusercontent.com/acme/skills/main/page.md\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAgentsSkillReachesTheModelAsItsInstructions() throws Exception {
        mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("content", NEW_SKILL)))).andExpect(status().isCreated());
        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Skilled Bot\",\"role\":\"API Reviewer\",\"specialization\":\"REST\"}")).andReturn();
        long agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/agents/" + agentId + "/resources/api-review")).andExpect(status().isNoContent());

        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Rivedi l'API contatti\",\"status\":\"OPEN\",\"priority\":\"LOW\"}")).andReturn();
        long taskId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        String tag = mockMvc.perform(put("/api/tasks/" + taskId + "/agent").header(HttpHeaders.IF_MATCH,
                        created.getResponse().getHeader(HttpHeaders.ETAG)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":" + agentId + "}")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());

        EngineClient.Request request = null;
        for (int i = 0; i < 100 && request == null; i++) {
            request = engine.requests().stream().filter(r -> r.system().contains("Skilled Bot")).findFirst().orElse(null);
            if (request == null) {
                Thread.sleep(50);
            }
        }
        assertThat(request).isNotNull();
        assertThat(request.system()).contains("Skill \"Revisione delle API REST\"").contains("Mai 200 per una creazione");

        // Let the run finish before the cleanup removes its agent.
        for (int i = 0; i < 100; i++) {
            String runs = mockMvc.perform(get("/api/tasks/" + taskId + "/runs")).andReturn().getResponse().getContentAsString();
            if (!runs.contains("QUEUED") && !runs.contains("RUNNING")) {
                break;
            }
            Thread.sleep(50);
        }
    }
}
