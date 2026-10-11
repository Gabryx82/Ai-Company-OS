package com.aicompany.backend.workspace;

import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 33 (ADR-039): the file explorer over HTTP, as an operator (the default client of
 * the suite): every file of the project folder can be listed, text is read, and nothing
 * leaves the folder, nothing is written, and a file of credentials is never read.
 */
class ProjectFileBrowserApiTest extends AbstractPostgresTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TaskRepository tasks;

    @TempDir
    Path folder;

    private long id;

    @BeforeEach
    void projectWithFiles() throws Exception {
        tasks.deleteAll();
        projects.deleteAll();
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Explorer\"}"))
                .andExpect(status().isCreated()).andReturn();
        id = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspacePath\":\"" + folder.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk());

        write("src/main/App.java", "class App {}\n");
        write("README.md", "# Explorer\n");
        write("zeta.txt", "\uFEFFcon il BOM\n");
        write(".env", "API_KEY=sk-live-should-never-leave\n");
        write(".env.example", "API_KEY=\n");
        write("keys/server.pem", "-----BEGIN PRIVATE KEY-----\n");
        Files.write(folder.resolve("logo.png"), PNG);
        Files.write(folder.resolve("data.bin"), new byte[]{1, 2, 0, 3});
        byte[] big = new byte[2_000_001];
        Arrays.fill(big, (byte) 'a');
        Files.write(folder.resolve("big.log"), big);
    }

    @Test
    void aFolderListsItsFoldersFirstThenItsFilesByName() throws Exception {
        mockMvc.perform(get("/api/projects/" + id + "/browse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(""))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.entries[*].name", contains("keys", "src", ".env", ".env.example", "big.log",
                        "data.bin", "logo.png", "README.md", "zeta.txt")))
                .andExpect(jsonPath("$.entries[0].kind").value("DIRECTORY"))
                .andExpect(jsonPath("$.entries[?(@.name == 'README.md')].size").value(11))
                .andExpect(jsonPath("$.entries[?(@.name == 'README.md')].path").value("README.md"));

        mockMvc.perform(get("/api/projects/" + id + "/browse").param("path", "src/main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("src/main"))
                .andExpect(jsonPath("$.entries[0].path").value("src/main/App.java"));

        mockMvc.perform(get("/api/projects/" + id + "/browse").param("path", "README.md"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTextFileIsReadWithoutItsByteOrderMark() throws Exception {
        mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", "src/main/App.java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("TEXT"))
                .andExpect(jsonPath("$.name").value("App.java"))
                .andExpect(jsonPath("$.content").value("class App {}\n"));
        mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", "zeta.txt"))
                .andExpect(jsonPath("$.content").value("con il BOM\n"));
    }

    @Test
    void aFileOfCredentialsIsListedButNeverRead() throws Exception {
        for (String secret : new String[]{".env", "keys/server.pem"}) {
            String body = mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", secret))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("WITHHELD"))
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("sk-live").doesNotContain("PRIVATE KEY");
        }
        mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", ".env.example"))
                .andExpect(jsonPath("$.kind").value("TEXT"))
                .andExpect(jsonPath("$.content").value("API_KEY=\n"));
        mockMvc.perform(get("/api/projects/" + id + "/browse/image").param("path", ".env"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void binaryAndLargeFilesSayWhyTheyAreNotShown() throws Exception {
        mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", "data.bin"))
                .andExpect(jsonPath("$.kind").value("BINARY"))
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.reason").value(containsString("binario")));
        mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", "big.log"))
                .andExpect(jsonPath("$.kind").value("TOO_LARGE"))
                .andExpect(jsonPath("$.size").value(2_000_001));
    }

    @Test
    void anImageIsServedAsItsBytesAndNothingElseIs() throws Exception {
        mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", "logo.png"))
                .andExpect(jsonPath("$.kind").value("IMAGE"))
                .andExpect(jsonPath("$.contentType").value("image/png"));
        mockMvc.perform(get("/api/projects/" + id + "/browse/image").param("path", "logo.png"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(content().bytes(PNG));
        mockMvc.perform(get("/api/projects/" + id + "/browse/image").param("path", "README.md"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nothingOutsideTheProjectFolderIsReachable() throws Exception {
        Path outside = Files.writeString(folder.getParent().resolve("outside-" + id + ".txt"), "secret");
        try {
            for (String endpoint : new String[]{"/browse", "/browse/file", "/browse/image"}) {
                for (String path : new String[]{"../outside-" + id + ".txt", outside.toString(), "src/../../x"}) {
                    mockMvc.perform(get("/api/projects/" + id + endpoint).param("path", path))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.type").value(containsString("workspace-path-refused")));
                }
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aLinkThatLeadsOutIsListedButNotFollowed() throws Exception {
        Path target = Files.createTempDirectory("aicos-outside");
        Files.writeString(target.resolve("inside-the-target.txt"), "secret");
        try {
            try {
                Files.createSymbolicLink(folder.resolve("escape"), target);
            } catch (UnsupportedOperationException | IOException | SecurityException noSymlinks) {
                assumeThat(false).as("symbolic links are not available here: " + noSymlinks).isTrue();
            }
            mockMvc.perform(get("/api/projects/" + id + "/browse"))
                    .andExpect(jsonPath("$.entries[?(@.name == 'escape')].kind").value("LINK"));
            mockMvc.perform(get("/api/projects/" + id + "/browse").param("path", "escape"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/projects/" + id + "/browse/file").param("path", "escape/inside-the-target.txt"))
                    .andExpect(status().isBadRequest());
        } finally {
            Files.deleteIfExists(folder.resolve("escape"));
            Files.deleteIfExists(target.resolve("inside-the-target.txt"));
            Files.deleteIfExists(target);
        }
    }

    @Test
    void aProjectWithoutAFolderHasNothingToBrowse() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No folder\"}"))
                .andExpect(status().isCreated()).andReturn();
        String other = created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", "");
        mockMvc.perform(get("/api/projects/" + other + "/browse"))
                .andExpect(status().isConflict());

        Files.walk(folder).sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        mockMvc.perform(get("/api/projects/" + id + "/browse"))
                .andExpect(status().isServiceUnavailable());
    }

    private void write(String relative, String text) throws IOException {
        Path path = folder.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
