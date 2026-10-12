package com.aicompany.backend.workspace;

import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeDesktop;
import com.aicompany.backend.task.repository.TaskRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 33 (ADR-040): the explorer's context menu over HTTP, as an operator. A file or a
 * folder is renamed in place, created empty, moved to the Recycle Bin -- never destroyed,
 * never over a name that exists -- and what AI Company OS stands on is left alone: the
 * structure of the workspace, git, the documents records point at, an archived project.
 */
class ProjectFileOperationsApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeDesktop desktop;

    @TempDir
    Path folder;

    private long id;

    @BeforeEach
    void projectWithFiles() throws Exception {
        desktop.reset();
        tasks.deleteAll();
        projects.deleteAll();
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Operations\"}"))
                .andExpect(status().isCreated()).andReturn();
        id = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspacePath\":\"" + folder.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk());

        write("src/main/App.java", "class App {}\n");
        write("README.md", "# Operations\n");
        write("notes/a.txt", "a");
        write("notes/b.txt", "bb");
        write("docs/tasks/T-1.md", "# T-1\n");
        write("MASTER_PROMPT.md", "# Master\n");
        write(".aicos/project.json", "{}");
        write(".git/config", "[core]\n");
    }

    @AfterEach
    void quietDesktop() {
        desktop.reset();
    }

    // --- rename -------------------------------------------------------------------------

    @Test
    void aFileAndAFolderAreRenamedInPlace() throws Exception {
        rename("README.md", "LEGGIMI.md")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("LEGGIMI.md"))
                .andExpect(jsonPath("$.kind").value("FILE"))
                .andExpect(jsonPath("$.size").value(13));
        assertThat(folder.resolve("LEGGIMI.md")).hasContent("# Operations");
        assertThat(folder.resolve("README.md")).doesNotExist();

        rename("notes", "appunti")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("appunti"))
                .andExpect(jsonPath("$.kind").value("DIRECTORY"));
        assertThat(folder.resolve("appunti/b.txt")).hasContent("bb");

        // Only the case changes: Windows sees one name, and the rename still happens.
        rename("LEGGIMI.md", "leggimi.md").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("leggimi.md"));
        try (var names = Files.list(folder)) {
            assertThat(names.map(p -> p.getFileName().toString())).contains("leggimi.md").doesNotContain("LEGGIMI.md");
        }
    }

    @Test
    void aRenameNeverWritesOverANameThatExists() throws Exception {
        rename("notes/a.txt", "b.txt")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-entry-exists"));
        assertThat(folder.resolve("notes/a.txt")).hasContent("a");
        assertThat(folder.resolve("notes/b.txt")).hasContent("bb");
    }

    @Test
    void aNameIsOneEntryOfOneFolderThatWindowsKeepsAsWritten() throws Exception {
        for (String name : new String[]{"../a.txt", "sub/a.txt", "a\\b.txt", "..", "con.txt", "NUL", "x.", "x ", "a:b",
                "a*b", "<x>", ".x.aicos-deleting-0"}) {
            rename("README.md", name)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }
        rename("README.md", " ").andExpect(status().isBadRequest());
        assertThat(folder.resolve("README.md")).exists();
    }

    @Test
    void theStructureOfTheWorkspaceAndGitAreNotRenamed() throws Exception {
        for (String path : new String[]{"MASTER_PROMPT.md", ".aicos/project.json", ".aicos", ".git", ".git/config"}) {
            rename(path, "other")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-path-refused"));
        }
        assertThat(folder.resolve("MASTER_PROMPT.md")).exists();
        assertThat(folder.resolve(".git/config")).exists();
    }

    @Test
    void aDocumentARecordPointsAtIsNotRenamedNorIsTheFolderThatHoldsIt() throws Exception {
        taskWithDocument("Scrivere il piano", "docs/tasks/T-1.md");

        rename("docs/tasks/T-1.md", "T-2.md")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-entry-in-use"))
                .andExpect(jsonPath("$.detail").value(containsString("Scrivere il piano")));
        rename("docs", "documenti").andExpect(status().isConflict());
        assertThat(folder.resolve("docs/tasks/T-1.md")).exists();
    }

    @Test
    void nothingLeavesTheProjectFolder() throws Exception {
        Path outside = folder.getParent().resolve("outside-" + System.nanoTime() + ".txt");
        Files.writeString(outside, "x");
        try {
            rename("../" + outside.getFileName(), "x.txt").andExpect(status().isBadRequest());
            rename(outside.toString(), "x.txt").andExpect(status().isBadRequest());
            create("..", "x.txt", "FILE").andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", "../" + outside.getFileName()))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "../" + outside.getFileName()))
                    .andExpect(status().isBadRequest());
            reveal("../" + outside.getFileName()).andExpect(status().isBadRequest());
            assertThat(outside).exists();
        } finally {
            Files.deleteIfExists(outside);
        }
        rename("", "x").andExpect(status().isBadRequest());
        rename("missing.txt", "x.txt").andExpect(status().isNotFound());
    }

    // --- new file, new folder ------------------------------------------------------------------

    @Test
    void anEmptyFileOrFolderIsCreatedNeverOverAName() throws Exception {
        create("src/main", "Other.java", "FILE")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("src/main/Other.java"))
                .andExpect(jsonPath("$.kind").value("FILE"))
                .andExpect(jsonPath("$.size").value(0));
        create("", "assets", "DIRECTORY")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("assets"))
                .andExpect(jsonPath("$.kind").value("DIRECTORY"));
        assertThat(folder.resolve("assets")).isDirectory();
        assertThat(folder.resolve("src/main/Other.java")).isEmptyFile();

        create("", "README.md", "FILE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-entry-exists"));
        assertThat(folder.resolve("README.md")).hasContent("# Operations");
        create("missing", "x.txt", "FILE").andExpect(status().isNotFound());
        create("", "x", "LINK").andExpect(status().isBadRequest());
        create(".git", "hooks-x", "FILE").andExpect(status().isBadRequest());
        create("README.md", "x.txt", "FILE").andExpect(status().isNotFound());
    }

    // --- the Recycle Bin -----------------------------------------------------------------------

    @Test
    void thePreviewSaysWhereItGoesAndHowItComesBack() throws Exception {
        mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", "README.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FILE"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.deletes[0]").value(containsString("Cestino di Windows")))
                .andExpect(jsonPath("$.keeps[0]").value(containsString("Si ripristina dal Cestino")))
                // Nothing is "removed from the disk": the dialog would say so.
                .andExpect(jsonPath("$.files").isEmpty());

        mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", "notes"))
                .andExpect(jsonPath("$.kind").value("FOLDER"))
                .andExpect(jsonPath("$.deletes[0]").value(containsString("2 file e 0 cartelle, 3 B")));

        mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", "MASTER_PROMPT.md"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(containsString("struttura del workspace")));
        mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", ".git"))
                .andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    void aFileOrAFolderGoesToTheRecycleBinAndStillExistsThere() throws Exception {
        mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "notes"))
                .andExpect(status().isNoContent());

        assertThat(folder.resolve("notes")).doesNotExist();
        assertThat(desktop.recycled()).containsExactly(folder.resolve("notes"));
        assertThat(desktop.inBin(0).resolve("b.txt")).hasContent("bb");

        mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "notes"))
                .andExpect(status().isNotFound());
    }

    @Test
    void whatSomethingStandsOnIsBlockedWithTheSameSentences() throws Exception {
        taskWithDocument("Scrivere il piano", "docs/tasks/T-1.md");

        mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", "docs"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockers").value(hasItem(containsString("Scrivere il piano"))));
        mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "docs/tasks/T-1.md"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:deletion-blocked"))
                .andExpect(jsonPath("$.detail").value(containsString("Scrivere il piano")));
        mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "MASTER_PROMPT.md"))
                .andExpect(status().isConflict());
        assertThat(desktop.recycled()).isEmpty();
        assertThat(folder.resolve("docs/tasks/T-1.md")).exists();
    }

    @Test
    void whereThereIsNoRecycleBinNothingIsRemoved() throws Exception {
        desktop.refuse("Il Cestino è disponibile solo quando il control plane gira su Windows.");

        mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", "README.md"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(containsString("solo quando il control plane gira su Windows")));
        mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "README.md"))
                .andExpect(status().isConflict());
        assertThat(folder.resolve("README.md")).exists();
    }

    @Test
    void aFileAnotherProgramHoldsIsReportedAsInUse() throws Exception {
        desktop.holdOpen();

        mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "README.md"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-entry-in-use"))
                .andExpect(jsonPath("$.detail").value(containsString("altro programma")));
        assertThat(folder.resolve("README.md")).exists();
    }

    @Test
    void anArchivedProjectsFilesAreReadAndNeverChanged() throws Exception {
        mockMvc.perform(post("/api/projects/" + id + "/archive").header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk());

        rename("README.md", "x.md")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:archived-project-is-immutable"));
        create("", "x.md", "FILE").andExpect(status().isConflict());
        mockMvc.perform(get("/api/projects/" + id + "/browse/deletion").param("path", "README.md"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(containsString("archiviato")));
        mockMvc.perform(delete("/api/projects/" + id + "/browse").param("path", "README.md"))
                .andExpect(status().isConflict());
        // Revealing changes nothing: it stays possible.
        reveal("README.md").andExpect(status().isNoContent());
        assertThat(folder.resolve("README.md")).exists();
        assertThat(folder.resolve("x.md")).doesNotExist();
    }

    // --- Explorer ---------------------------------------------------------------------------

    @Test
    void explorerOpensOnTheItemOrOnTheProjectFolder() throws Exception {
        reveal("src/main/App.java").andExpect(status().isNoContent());
        reveal("").andExpect(status().isNoContent());
        reveal("missing.txt").andExpect(status().isNotFound());

        assertThat(desktop.revealed()).containsExactly(folder.resolve("src/main/App.java"), folder.toAbsolutePath().normalize());
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions rename(String path, String name) throws Exception {
        return mockMvc.perform(post("/api/projects/" + id + "/browse/rename").contentType(MediaType.APPLICATION_JSON)
                .content(json(new String[][]{{"path", path}, {"name", name}})));
    }

    private ResultActions create(String parent, String name, String kind) throws Exception {
        return mockMvc.perform(post("/api/projects/" + id + "/browse/create").contentType(MediaType.APPLICATION_JSON)
                .content(json(new String[][]{{"parent", parent}, {"name", name}, {"kind", kind}})));
    }

    private ResultActions reveal(String path) throws Exception {
        return mockMvc.perform(post("/api/projects/" + id + "/browse/reveal").contentType(MediaType.APPLICATION_JSON)
                .content(json(new String[][]{{"path", path}})));
    }

    private void taskWithDocument(String title, String document) throws Exception {
        long task = JsonMapper.builder().build().readTree(mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"LOW\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
        jdbc.update("UPDATE tasks SET project_id = ?, document_path = ? WHERE id = ?", id, document, task);
    }

    private static String json(String[][] fields) {
        var node = JsonMapper.builder().build().createObjectNode();
        for (String[] field : fields) {
            node.put(field[0], field[1]);
        }
        return node.toString();
    }

    private void write(String relative, String content) throws IOException {
        Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
