package com.aicompany.backend.workspace;

import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The project workspace over HTTP (ADR-020). */
class ProjectWorkspaceApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private FakeHost host;

    @TempDir
    Path folder;

    @BeforeEach
    void clean() {
        tasks.deleteAll();
        projects.deleteAll();
    }

    private long createProject(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(result.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
    }

    private void profile(long id, String json) throws Exception {
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @Test
    void aNewProjectStartsGuidedWithNoPlanAndNoType() throws Exception {
        long id = createProject("Demo");

        mockMvc.perform(get("/api/projects/" + id))
                .andExpect(jsonPath("$.autonomyLevel").value("GUIDED"))
                .andExpect(jsonPath("$.planStatus").value("NONE"))
                .andExpect(jsonPath("$.projectType").doesNotExist());
    }

    @Test
    void theProfileIsSetUnderThePreconditionAndDefaultsTheFolderUnderTheRoot() throws Exception {
        long id = createProject("Gestionale Àlfa");

        mockMvc.perform(put("/api/projects/" + id + "/profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectType\":\"BACKEND\"}"))
                .andExpect(status().isPreconditionRequired());

        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectType\":\"BACKEND\",\"stack\":\"Spring Boot\",\"autonomyLevel\":\"SUPERVISED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectType").value("BACKEND"))
                .andExpect(jsonPath("$.autonomyLevel").value("SUPERVISED"))
                .andExpect(jsonPath("$.workspacePath").value(org.hamcrest.Matchers.endsWith("gestionale-alfa")));
    }

    @Test
    void aRelativeFolderIsRefused() throws Exception {
        long id = createProject("Demo");

        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"workspacePath\":\"relative/dir\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.workspacePath").exists());
    }

    @Test
    void scaffoldingCreatesTheGovernedStructureAndNeverOverwrites() throws Exception {
        long id = createProject("Demo");
        Files.writeString(folder.resolve("CLAUDE.md"), "the operator's own instructions");
        profile(id, "{\"projectType\":\"WEB_APP\",\"workspacePath\":" + json(folder) + "}");

        mockMvc.perform(post("/api/projects/" + id + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.path == 'CLAUDE.md')].outcome").value("EXISTING"))
                .andExpect(jsonPath("$[?(@.path == 'AGENTS.md')].outcome").value("CREATED"));

        assertThat(Files.readString(folder.resolve("CLAUDE.md"))).isEqualTo("the operator's own instructions");
        assertThat(folder.resolve("references/design-targets")).isDirectory();
        assertThat(Files.readString(folder.resolve("AGENTS.md"))).contains("Demo").contains("GUIDED");
        assertThat(Files.readString(folder.resolve(".aicos/project.json"))).contains("\"aicosProjectId\" : " + id);

        mockMvc.perform(post("/api/projects/" + id + "/workspace"))
                .andExpect(jsonPath("$[?(@.path == 'AGENTS.md')].outcome").value("EXISTING"));
    }

    @Test
    void theMasterPromptIsWrittenAndReadBackAndTheTemplateIsRecognised() throws Exception {
        long id = createProject("Demo");
        profile(id, "{\"workspacePath\":" + json(folder) + "}");
        mockMvc.perform(post("/api/projects/" + id + "/workspace")).andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/" + id + "/documents"))
                .andExpect(jsonPath("$.masterPromptIsTemplate").value(true));

        mockMvc.perform(put("/api/projects/" + id + "/files").param("path", "MASTER_PROMPT.md")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# Master prompt\\nUn gestionale per officine.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("MASTER_PROMPT.md"));

        mockMvc.perform(get("/api/projects/" + id + "/files").param("path", "MASTER_PROMPT.md"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("officine")));
        mockMvc.perform(get("/api/projects/" + id + "/documents"))
                .andExpect(jsonPath("$.masterPromptIsTemplate").value(false))
                .andExpect(jsonPath("$.masterPrompt.path").value("MASTER_PROMPT.md"));
    }

    @Test
    void referenceImagesAreListedAndServedAsImages() throws Exception {
        long id = createProject("Demo");
        profile(id, "{\"workspacePath\":" + json(folder) + "}");
        mockMvc.perform(post("/api/projects/" + id + "/workspace")).andExpect(status().isOk());
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        Files.write(folder.resolve("references/images/hero.png"), png);

        mockMvc.perform(get("/api/projects/" + id + "/documents"))
                .andExpect(jsonPath("$.references[*].path", hasItems("references/images/hero.png")));
        mockMvc.perform(get("/api/projects/" + id + "/files").param("path", "references/images/hero.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(png));
    }

    @Test
    void thePathGuardsHoldOverHttp() throws Exception {
        long id = createProject("Demo");
        profile(id, "{\"workspacePath\":" + json(folder) + "}");

        mockMvc.perform(get("/api/projects/" + id + "/files").param("path", "../../etc/passwd"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-path-refused"));
        mockMvc.perform(put("/api/projects/" + id + "/files").param("path", "src/Evil.java")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/projects/" + id + "/files").param("path", "tasks/missing.md"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aProjectWithoutAFolderSaysSoAndItsLaunchesAreRefused() throws Exception {
        long id = createProject("Demo");

        mockMvc.perform(get("/api/projects/" + id + "/documents"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-not-configured"));
        mockMvc.perform(post("/api/software/postman/launch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":" + id + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anIdeOpensInTheProjectFolderResolvedFromTheDatabase() throws Exception {
        long id = createProject("Demo");
        profile(id, "{\"workspacePath\":" + json(folder) + "}");

        mockMvc.perform(post("/api/software/vscode-continue/launch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":" + id + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.folderOpened").value(true));

        assertThat(host.launches().getFirst().command()).containsExactly(
                FakeHost.FAKE_EXECUTABLE.toString(), folder.toString());
    }

    @Test
    void anArchivedProjectsDocumentsAreFrozen() throws Exception {
        long id = createProject("Demo");
        profile(id, "{\"workspacePath\":" + json(folder) + "}");
        mockMvc.perform(post("/api/projects/" + id + "/archive").header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/projects/" + id + "/files").param("path", "MASTER_PROMPT.md")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:archived-project-is-immutable"));
    }

    @Test
    void theProjectTypesCarryTheProposedStack() throws Exception {
        mockMvc.perform(get("/api/catalog/project-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[?(@.type == 'FULL_STACK')].software[*]", hasItems("claude-code", "postman")));
    }

    private static String json(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\") + "\"";
    }

    @SuppressWarnings("unused")
    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
