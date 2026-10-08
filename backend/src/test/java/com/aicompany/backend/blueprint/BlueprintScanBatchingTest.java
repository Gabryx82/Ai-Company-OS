package com.aicompany.backend.blueprint;

import com.aicompany.backend.support.AbstractPostgresTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 (TD-60): a scan writes its blocks and relations in JDBC batches, not
 * one statement per row -- on this repository that was about 12 s for 614 blocks
 * and 2289 relations.
 */
class BlueprintScanBatchingTest extends AbstractPostgresTest {

    private static final int CLASSES = 120;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aScanOfAHundredAndTwentyLinkedClassesPreparesAFewDozenStatementsNotOnePerRow() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Batch " + System.nanoTime() + "\"}")).andExpect(status().isCreated()).andReturn();
        long projectId = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"workspacePath\":\""
                        + folder.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        Path pkg = folder.resolve("src/main/java/com/acme/chain");
        Files.createDirectories(pkg);
        for (int i = 0; i < CLASSES; i++) {
            String next = "Link" + ((i + 1) % CLASSES);
            Files.writeString(pkg.resolve("Link" + i + ".java"), """
                    package com.acme.chain;

                    public class Link%d {
                        private final %s next;
                        public Link%d(%s next) { this.next = next; }
                        public int depth() { return next.depth() + 1; }
                    }
                    """.formatted(i, next, i, next));
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        JsonNode scan;
        try {
            scan = json.readTree(mockMvc.perform(post("/api/projects/" + projectId + "/blueprint/scan"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        long blocks = scan.path("nodesDetected").asLong();
        long relations = scan.path("edgesDetected").asLong();
        assertThat(blocks).isGreaterThanOrEqualTo(CLASSES);
        assertThat(relations).isGreaterThanOrEqualTo(CLASSES);
        assertThat(statistics.getEntityInsertCount()).isGreaterThanOrEqualTo(blocks + relations);
        assertThat(statistics.getPrepareStatementCount())
                .as("%d blocks and %d relations written in batches", blocks, relations)
                .isLessThan((blocks + relations) / 4);
    }
}
