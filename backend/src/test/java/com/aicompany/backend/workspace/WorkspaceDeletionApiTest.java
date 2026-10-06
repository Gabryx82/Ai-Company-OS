package com.aicompany.backend.workspace;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 30 (ADR-035 §5-6): files of a project are deleted from the console --
 * references from {@code references/} and only from there, documents that no
 * record points at -- without Explorer, and without a path that leaves the
 * project.
 */
class WorkspaceDeletionApiTest extends AbstractPostgresTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final String REFUSED = "urn:ai-company-os:problem:workspace-path-refused";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path folder;

    private long projectId;

    @BeforeEach
    void aProjectWithAWorkspace() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Deletion " + System.nanoTime() + "\"}")).andReturn();
        projectId = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"WEB_APP\",\"autonomyLevel\":\"GUIDED\",\"workspacePath\":\""
                        + folder.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + projectId + "/workspace")).andExpect(status().isOk());
    }

    private String upload(String folderName, String name) throws Exception {
        MvcResult stored = mockMvc.perform(post("/api/projects/" + projectId + "/references").param("folder", folderName)
                        .param("name", name).contentType(MediaType.IMAGE_PNG).content(PNG))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(stored.getResponse().getContentAsString(), "$.path");
    }

    // --- references --------------------------------------------------------------------------

    @Test
    void anImageIsDeletedFromDiskAndDisappearsFromTheListing() throws Exception {
        String path = upload("images", "hero");
        assertThat(folder.resolve(path)).exists();

        mockMvc.perform(delete("/api/projects/" + projectId + "/references").param("path", path))
                .andExpect(status().isNoContent());

        assertThat(folder.resolve(path)).doesNotExist();
        // Nothing half-deleted is left next to it once the transaction is over.
        try (var left = Files.list(folder.resolve("references/images"))) {
            assertThat(left.map(p -> p.getFileName().toString())).noneMatch(n -> n.contains("aicos-deleting"));
        }
        mockMvc.perform(get("/api/projects/" + projectId + "/documents"))
                .andExpect(jsonPath("$.references[?(@.path == '" + path + "')]").isEmpty());
    }

    @Test
    void aReferenceThatIsNotThereIsA404() throws Exception {
        mockMvc.perform(delete("/api/projects/" + projectId + "/references").param("path", "references/images/ghost.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-file-not-found"));
        mockMvc.perform(delete("/api/projects/987654/references").param("path", "references/images/ghost.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nothingOutsideReferencesCanBeDeletedThisWay() throws Exception {
        Path outside = folder.getParent().resolve("outside-" + System.nanoTime() + ".png");
        Files.write(outside, PNG);
        try {
            for (String attempt : new String[]{"references/../MASTER_PROMPT.md", "references/images/../../AGENTS.md",
                    "MASTER_PROMPT.md", "docs/x.md", "../" + outside.getFileName(), outside.toString(),
                    "references/../../" + outside.getFileName(), "references/images", "references"}) {
                mockMvc.perform(delete("/api/projects/" + projectId + "/references").param("path", attempt))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.type").value(REFUSED));
            }
            assertThat(folder.resolve("MASTER_PROMPT.md")).exists();
            assertThat(folder.resolve("AGENTS.md")).exists();
            assertThat(folder.resolve("references/images")).isDirectory();
            assertThat(outside).exists();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aSelectionGoesWholeAndFilesAlreadyGoneAreSkipped() throws Exception {
        String a = upload("mockups", "one");
        String b = upload("screenshots", "two");
        Files.writeString(folder.resolve("references/notes.md"), "# note", StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/projects/" + projectId + "/documents"))
                .andExpect(jsonPath("$.referenceFiles[?(@.path == 'references/notes.md')]").exists())
                .andExpect(jsonPath("$.referenceFiles[?(@.path == '" + a + "')]").isEmpty());

        // One path out of bounds refuses the whole selection before anything moves.
        mockMvc.perform(post("/api/projects/" + projectId + "/references/bulk-delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"" + a + "\",\"references/../AGENTS.md\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(folder.resolve(a)).exists();

        mockMvc.perform(post("/api/projects/" + projectId + "/references/bulk-delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[\"" + a + "\",\"" + b + "\",\"references/notes.md\",\"references/images/gone.png\"]}"))
                .andExpect(status().isNoContent());
        assertThat(folder.resolve(a)).doesNotExist();
        assertThat(folder.resolve(b)).doesNotExist();
        assertThat(folder.resolve("references/notes.md")).doesNotExist();

        mockMvc.perform(post("/api/projects/" + projectId + "/references/bulk-delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paths\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anArchivedProjectKeepsItsReferences() throws Exception {
        String path = upload("images", "kept");
        jdbc.update("UPDATE projects SET status = 'ARCHIVED' WHERE id = ?", projectId);
        mockMvc.perform(delete("/api/projects/" + projectId + "/references").param("path", path))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:archived-project-is-immutable"));
        assertThat(folder.resolve(path)).exists();
    }

    // --- documents ---------------------------------------------------------------------------

    @Test
    void aDocumentATaskPointsAtStaysUntilTheTaskIsGone() throws Exception {
        Files.createDirectories(folder.resolve("tasks"));
        Files.writeString(folder.resolve("tasks/TASK-001.md"), "# Task", StandardCharsets.UTF_8);
        long task = jdbc.queryForObject("INSERT INTO tasks (title, status, priority, project_id, document_path) "
                + "VALUES ('Owner of the document', 'OPEN', 'LOW', ?, 'tasks/TASK-001.md') RETURNING id", Long.class, projectId);

        mockMvc.perform(get("/api/projects/" + projectId + "/files/deletion").param("path", "tasks/TASK-001.md"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("Owner of the document")));
        mockMvc.perform(delete("/api/projects/" + projectId + "/files").param("path", "tasks/TASK-001.md"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:deletion-blocked"));
        assertThat(folder.resolve("tasks/TASK-001.md")).exists();

        jdbc.update("DELETE FROM tasks WHERE id = ?", task);
        mockMvc.perform(get("/api/projects/" + projectId + "/files/deletion").param("path", "tasks/TASK-001.md"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.files[0]").value("tasks/TASK-001.md"));
        mockMvc.perform(delete("/api/projects/" + projectId + "/files").param("path", "tasks/TASK-001.md"))
                .andExpect(status().isNoContent());
        assertThat(folder.resolve("tasks/TASK-001.md")).doesNotExist();
        mockMvc.perform(delete("/api/projects/" + projectId + "/files").param("path", "tasks/TASK-001.md"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theStructureOfTheWorkspaceIsEditedNeverDeleted() throws Exception {
        for (String attempt : new String[]{"MASTER_PROMPT.md", "AGENTS.md", "CLAUDE.md", ".aicos/project.json",
                ".aicos/CONTEXT_MAP.md", "docs/../MASTER_PROMPT.md", "docs/phases", "references/README.md"}) {
            mockMvc.perform(delete("/api/projects/" + projectId + "/files").param("path", attempt))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(REFUSED));
        }
        assertThat(folder.resolve("MASTER_PROMPT.md")).exists();
        assertThat(folder.resolve(".aicos/project.json")).exists();
    }
}
