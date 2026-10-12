package com.aicompany.backend.software;

import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.repository.SoftwareRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import com.aicompany.backend.support.FakeHost;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 33 (ADR-040): «Apri con» over HTTP. A launch may carry one file of a project,
 * which goes where the catalog's {@code fileArgs} put it; nothing else of the request
 * reaches the command line, and nothing outside the project folder is ever passed.
 */
class OpenWithApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeHost host;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private SoftwareRepository software;

    @TempDir
    Path folder;

    private long id;

    @BeforeEach
    void projectWithAFile() throws Exception {
        host.reset();
        software.findByKey("bad-opener").ifPresent(software::delete);
        tasks.deleteAll();
        projects.deleteAll();
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Open with\"}"))
                .andExpect(status().isCreated()).andReturn();
        id = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspacePath\":\"" + folder.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk());
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/App.java"), "class App {}\n");
    }

    @AfterEach
    void forget() {
        host.reset();
        software.findByKey("bad-opener").ifPresent(software::delete);
    }

    @Test
    void theShippedCatalogSaysHowEachProgramOpensAFileAndWhatItSuits() throws Exception {
        mockMvc.perform(get("/api/software/netbeans"))
                .andExpect(jsonPath("$.fileArgs", contains("--open", "{file}")))
                .andExpect(jsonPath("$.fileTypes", hasItem(".jsp")))
                .andExpect(jsonPath("$.opensFiles").value(true));
        mockMvc.perform(get("/api/software/vscode-continue"))
                .andExpect(jsonPath("$.fileArgs", contains("{folder}", "{file}")))
                .andExpect(jsonPath("$.fileTypes", hasItem("dockerfile")))
                .andExpect(jsonPath("$.fileTypes", hasItem("*")));
        mockMvc.perform(get("/api/software/postman"))
                .andExpect(jsonPath("$.fileArgs").isEmpty())
                .andExpect(jsonPath("$.opensFiles").value(false));
    }

    @Test
    void aFileOfTheProjectOpensWhereTheCatalogPutsIt() throws Exception {
        open("vscode-continue", "{\"projectId\":" + id + ",\"path\":\"src/App.java\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileOpened").value(true))
                .andExpect(jsonPath("$.folderOpened").value(true))
                .andExpect(jsonPath("$.command", contains(FakeHost.FAKE_EXECUTABLE.toString(),
                        folder.toAbsolutePath().normalize().toString(), folder.resolve("src/App.java").toString())));

        LaunchPlan plan = host.launches().getLast();
        assertThat(plan.file()).isEqualTo(folder.resolve("src/App.java"));
        assertThat(plan.workingDirectory()).isEqualTo(folder.toAbsolutePath().normalize());

        // A plain launch is what it was.
        open("vscode-continue", "{\"projectId\":" + id + "}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileOpened").value(false));
    }

    @Test
    void aPathNeedsItsProjectAndStaysInsideItsFolder() throws Exception {
        open("intellij-junie", "{\"path\":\"src/App.java\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.projectId").exists());
        for (String path : new String[]{"../outside.txt", "src/../../outside.txt", "C:/Windows/win.ini", "/etc/hosts"}) {
            open("intellij-junie", "{\"projectId\":" + id + ",\"path\":\"" + path + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-path-refused"));
        }
        open("intellij-junie", "{\"projectId\":" + id + ",\"path\":\"src\"}")
                .andExpect(status().isBadRequest());
        open("intellij-junie", "{\"projectId\":" + id + ",\"path\":\"src/Missing.java\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:workspace-file-not-found"));
        assertThat(host.launches()).isEmpty();
    }

    @Test
    void aProgramThatTakesNoFileIsNotAskedTo() throws Exception {
        open("postman", "{\"projectId\":" + id + ",\"path\":\"src/App.java\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:software-not-launchable"));

        // Found through the Start menu only: explorer.exe takes no file for it.
        host.script("vscode-continue", Detection.of(Availability.INSTALLED, "Start menu: Microsoft.VisualStudioCode"));
        mockMvc.perform(get("/api/software/vscode-continue")).andExpect(jsonPath("$.opensFiles").value(false));
        open("vscode-continue", "{\"projectId\":" + id + ",\"path\":\"src/App.java\"}")
                .andExpect(status().isConflict());
        assertThat(host.launches()).isEmpty();
    }

    @Test
    void theCatalogRefusesFileArgumentsThatAreNotWholePlaceholders() throws Exception {
        String admin = AdminSession.bearer(mockMvc);
        for (String args : new String[]{"[\"--goto={file}\"]", "[\"{folder}\"]", "[\"{file}\",\"{file}\"]"}) {
            mockMvc.perform(post("/api/software").header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"key":"bad-opener","name":"Bad","category":"EDITOR","role":"x","launchKind":"DESKTOP",
                                     "executable":"C:\\\\x\\\\bad.exe","fileArgs":%s}""".formatted(args)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.fileArgs").exists());
        }
        mockMvc.perform(post("/api/software").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"key":"bad-opener","name":"Bad","category":"TERMINAL","role":"x","launchKind":"CLI",
                                 "cliCommand":"bad","fileArgs":["{file}"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fileArgs").exists());
        mockMvc.perform(post("/api/software").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"key":"bad-opener","name":"Good","category":"EDITOR","role":"x","launchKind":"DESKTOP",
                                 "executable":"C:\\\\x\\\\good.exe","fileArgs":["-n","{file}"],"fileTypes":[".TXT"," .log "]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileArgs", contains("-n", "{file}")))
                .andExpect(jsonPath("$.fileTypes", contains(".txt", ".log")));
    }

    private ResultActions open(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/software/" + key + "/launch").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
