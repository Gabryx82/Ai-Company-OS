package com.aicompany.backend.api;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 -- TD-33: a listing carries each element's version, so writing N
 * elements back costs N writes, not N reads first. The version in the listing is
 * the one the element's own ETag carries, and it is accepted as {@code If-Match}.
 */
class ListingVersionTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode find(JsonNode list, long id) {
        for (JsonNode element : list) {
            if (element.path("id").asLong() == id) {
                return element;
            }
        }
        throw new AssertionError("element " + id + " not in the listing");
    }

    @Test
    void projectsTasksAndAgentsAreWrittenBackFromTheListingAlone() throws Exception {
        long project = body(mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Listing " + System.nanoTime() + "\"}")).andExpect(status().isCreated())).path("id").asLong();
        long task = body(mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"From the listing\",\"status\":\"OPEN\",\"priority\":\"LOW\"}")).andExpect(status().isCreated())).path("id").asLong();
        long agent = body(mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"listing-" + System.nanoTime() + "\",\"role\":\"BACKEND\",\"specialization\":\"lists\"}"))
                .andExpect(status().isCreated())).path("id").asLong();

        writeBackFromListing("/api/projects", project, "{\"name\":\"Renamed from the listing " + System.nanoTime() + "\"}");
        writeBackFromListing("/api/tasks", task, "{\"title\":\"Renamed from the listing\",\"priority\":\"HIGH\"}");
        writeBackFromListing("/api/agents", agent, "{\"name\":\"renamed-" + System.nanoTime() + "\",\"role\":\"BACKEND\",\"specialization\":\"lists\"}");
    }

    private void writeBackFromListing(String collection, long id, String update) throws Exception {
        JsonNode listed = find(body(mockMvc.perform(get(collection)).andExpect(status().isOk())), id);
        assertThat(listed.has("version")).as(collection + " lists the version").isTrue();
        long version = listed.path("version").asLong();

        String etag = mockMvc.perform(get(collection + "/" + id)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).as("the element's own tag carries the same version").startsWith("\"" + version);

        JsonNode written = body(mockMvc.perform(put(collection + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"").content(update)).andExpect(status().isOk()));
        assertThat(written.path("version").asLong()).isGreaterThan(version);

        // And the version it was written from is now stale.
        mockMvc.perform(put(collection + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.IF_MATCH, "\"" + version + "\"").content(update)).andExpect(status().isPreconditionFailed());
    }
}
