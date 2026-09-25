package com.aicompany.backend.workspace;

import com.aicompany.backend.support.AbstractPostgresTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PHASE 25: images into references/, from the console -- the bytes decide what a file is. */
class ReferencesApiTest extends AbstractPostgresTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path folder;

    private long projectId;

    @BeforeEach
    void aProject() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Reference " + System.nanoTime() + "\"}")).andReturn();
        projectId = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"THREE_D\",\"autonomyLevel\":\"GUIDED\",\"workspacePath\":\""
                        + folder.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + projectId + "/workspace")).andExpect(status().isOk());
    }

    @Test
    void anImageIsStoredUnderItsFolderWithASafeNameAndNeverOverwritten() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/references").param("folder", "mockups")
                        .param("name", "Home Page (v2).PNG").contentType(MediaType.IMAGE_PNG).content(PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.path").value("references/mockups/home-page-v2.png"));
        mockMvc.perform(post("/api/projects/" + projectId + "/references").param("folder", "mockups")
                        .param("name", "Home Page (v2).PNG").contentType(MediaType.IMAGE_PNG).content(PNG))
                .andExpect(jsonPath("$.path").value("references/mockups/home-page-v2-2.png"));
        assertThat(Files.readAllBytes(folder.resolve("references/mockups/home-page-v2.png"))).isEqualTo(PNG);

        mockMvc.perform(get("/api/projects/" + projectId + "/documents"))
                .andExpect(jsonPath("$.references[?(@.path == 'references/mockups/home-page-v2.png')]").exists());
    }

    @Test
    void aFileThatIsNotAnImageIsRefusedWhateverItIsCalled() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/references").param("name", "photo.png")
                        .contentType(MediaType.IMAGE_PNG).content("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects/" + projectId + "/references").param("folder", "../../etc")
                        .contentType(MediaType.IMAGE_PNG).content(PNG))
                .andExpect(status().isBadRequest());
    }
}
