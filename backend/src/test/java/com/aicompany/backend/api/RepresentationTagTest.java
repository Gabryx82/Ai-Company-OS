package com.aicompany.backend.api;

import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 -- TD-32: the entity-tag follows the representation's bytes, not only the
 * row's version, and preconditions still compare states (ADR-009).
 */
class RepresentationTagTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeHost host;

    private String tag(String path) throws Exception {
        return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    @Test
    void theSameRowShownDifferentlyHasADifferentTagWithTheSameState() throws Exception {
        // A software entry shows what detection sees now, which is never stored: the row stays at
        // its version while its representation changes.
        host.reset();
        String installed = tag("/api/software/postman");
        host.script("postman", Detection.of(Availability.NOT_INSTALLED, "uninstalled meanwhile"));
        String gone = tag("/api/software/postman");
        host.reset();

        assertThat(installed).matches("\"\\d+\\.[0-9a-f]{16}\"");
        assertThat(gone).isNotEqualTo(installed);
        assertThat(gone.substring(0, gone.indexOf('.'))).isEqualTo(installed.substring(0, installed.indexOf('.')));
        assertThat(tag("/api/software/postman")).as("the same bytes, the same tag").isEqualTo(installed);
    }

    @Test
    void ifMatchTakesTheFullTagOrTheBareVersionAndRefusesAForgedOne() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        long id = json.readTree(mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tags " + System.nanoTime() + "\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").asLong();
        String full = tag("/api/projects/" + id);
        String version = full.substring(1, full.indexOf('.'));

        mockMvc.perform(put("/api/projects/" + id).header(HttpHeaders.IF_MATCH, "\"" + version + ".zz\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Forged " + System.nanoTime() + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/projects/" + id).header(HttpHeaders.IF_MATCH, full)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Full tag " + System.nanoTime() + "\"}"))
                .andExpect(status().isOk());
        String next = tag("/api/projects/" + id);
        mockMvc.perform(put("/api/projects/" + id).header(HttpHeaders.IF_MATCH, "\"" + next.substring(1, next.indexOf('.')) + "\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bare version " + System.nanoTime() + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/projects/" + id).header(HttpHeaders.IF_MATCH, full)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Stale " + System.nanoTime() + "\"}"))
                .andExpect(status().isPreconditionFailed());
    }

    /** What makes a write's answer and the next read byte-identical: entities record time as the database keeps it. */
    @Test
    void noEntityRecordsATimeWithMorePrecisionThanTheDatabaseKeeps() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java") && p.toString().contains("model")).toList()) {
                if (Files.readString(source, StandardCharsets.UTF_8).contains("Instant.now()")) {
                    offenders.add(source.toString());
                }
            }
        }
        assertThat(offenders).as("use DatabaseClock.now()").isEmpty();
    }
}
