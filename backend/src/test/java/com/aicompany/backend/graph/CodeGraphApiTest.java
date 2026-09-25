package com.aicompany.backend.graph;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PHASE 23 (ADR-030): the code graph of a project folder, and where it lands. */
class CodeGraphApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long projectId;

    @BeforeEach
    void aProjectWithCode() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Codice " + System.nanoTime() + "\"}")).andReturn();
        projectId = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"FULL_STACK\",\"autonomyLevel\":\"GUIDED\",\"workspacePath\":\""
                        + folder.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + projectId + "/workspace")).andExpect(status().isOk());

        write("web/src/main.tsx", "import { App } from './App';\nimport React from 'react';\n");
        write("web/src/App.tsx", "import { api } from './api/client';\nimport { format } from 'date-fns/format';\n");
        write("web/src/api/client.ts", "import { App } from '../App';\nexport const api = 1;\n");
        write("web/package.json", "{ \"dependencies\": { \"react\": \"19\", \"date-fns\": \"4\" } }");
        write("server/src/main/java/com/acme/Service.java",
                "package com.acme;\nimport com.acme.repo.Store;\nimport org.springframework.stereotype.Service;\n");
        write("server/src/main/java/com/acme/repo/Store.java", "package com.acme.repo;\nimport java.util.List;\n");
        write("engine/app/main.py", "from app.models import Model\nimport requests\nimport os\n");
        write("engine/app/models.py", "from . import util\n");
        write("engine/app/util.py", "");
        write("web/node_modules/big/index.js", "import './ignored';\n");
    }

    private void write(String relative, String content) throws Exception {
        Path file = folder.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void theGraphHasTheFilesTheirImportsTheExternalsAndTheCycles() throws Exception {
        mockMvc.perform(get("/api/projects/" + projectId + "/code-graph")).andExpect(status().isNoContent());

        MvcResult result = mockMvc.perform(post("/api/projects/" + projectId + "/code-graph")).andExpect(status().isOk()).andReturn();
        JsonNode graph = json.readTree(result.getResponse().getContentAsString());

        assertThat(graph.path("files").asInt()).isEqualTo(8);
        assertThat(graph.path("languages").path("typescript").asInt()).isEqualTo(3);
        assertThat(graph.toString()).doesNotContain("node_modules");

        String edges = graph.path("edges").toString();
        assertThat(edges).contains("{\"source\":\"web/src/main.tsx\",\"target\":\"web/src/App.tsx\",\"kind\":\"IMPORT\"}")
                .contains("{\"source\":\"server/src/main/java/com/acme/Service.java\",\"target\":\"server/src/main/java/com/acme/repo/Store.java\",\"kind\":\"IMPORT\"}")
                .contains("{\"source\":\"engine/app/main.py\",\"target\":\"engine/app/models.py\",\"kind\":\"IMPORT\"}")
                .contains("\"target\":\"ext:npm:date-fns\"")
                .contains("\"target\":\"ext:java:org.springframework\"")
                .contains("\"target\":\"ext:pypi:requests\"")
                .doesNotContain("ext:pypi:os")
                .doesNotContain("ext:java:java.util");

        assertThat(graph.path("cycles").toString()).contains("web/src/App.tsx").contains("web/src/api/client.ts");
        assertThat(Files.readString(folder.resolve(".aicos/CODE_GRAPH.md"))).contains("Cicli di import").contains("date-fns");

        mockMvc.perform(get("/api/projects/" + projectId + "/code-graph")).andExpect(status().isOk());
    }
}
