package com.aicompany.backend.daily;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Daily Work over HTTP (directive §18). */
class DailyApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper json = JsonMapper.builder().build();

    private long task(String title) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"HIGH\"}")).andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    @Test
    void aDayHoldsPersonalItemsAndReferencesToProjectTasksWithoutCopyingThem() throws Exception {
        long taskId = task("Implementare DB progetto X");

        mockMvc.perform(post("/api/daily").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"2026-09-24\",\"taskId\":" + taskId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Implementare DB progetto X"))
                .andExpect(jsonPath("$.task.status").value("OPEN"));
        mockMvc.perform(post("/api/daily").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"2026-09-24\",\"title\":\"Chiamare il commercialista\",\"priority\":\"LOW\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/daily").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"2026-09-25\",\"title\":\"Servlet progetto X2\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/daily").param("from", "2026-09-24").param("to", "2026-09-24"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].task.id").value(taskId))
                .andExpect(jsonPath("$[1].task").doesNotExist());
        mockMvc.perform(get("/api/daily").param("from", "2026-09-24").param("to", "2026-09-25"))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void aPersonalItemNeedsATitleAndAnUnknownTaskIsA404() throws Exception {
        mockMvc.perform(post("/api/daily").contentType(MediaType.APPLICATION_JSON).content("{\"day\":\"2026-09-24\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists());
        mockMvc.perform(post("/api/daily").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"2026-09-24\",\"taskId\":999999}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void itemsChangeUnderThePreconditionAndUnfinishedWorkMovesToTomorrow() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/daily").contentType(MediaType.APPLICATION_JSON)
                .content("{\"day\":\"2026-09-24\",\"title\":\"Test progetto Y\"}")).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(post("/api/daily").contentType(MediaType.APPLICATION_JSON)
                .content("{\"day\":\"2026-09-24\",\"title\":\"Fatto\",\"status\":\"DONE\"}"));
        MvcResult done = mockMvc.perform(get("/api/daily").param("from", "2026-09-24").param("to", "2026-09-24")).andReturn();
        long doneId = json.readTree(done.getResponse().getContentAsString()).path(1).path("id").asLong();
        mockMvc.perform(put("/api/daily/" + doneId).header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Fatto\",\"status\":\"DONE\"}")).andExpect(status().isOk());

        mockMvc.perform(put("/api/daily/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test progetto Y\",\"status\":\"DOING\"}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/daily/" + id).header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Test progetto Y\",\"status\":\"DOING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOING"));

        mockMvc.perform(post("/api/daily/carry-over").param("from", "2026-09-24").param("to", "2026-09-25"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].day").value("2026-09-25"));
        mockMvc.perform(get("/api/daily").param("from", "2026-09-24").param("to", "2026-09-24"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("DONE"));

        mockMvc.perform(delete("/api/daily/" + id).header(HttpHeaders.IF_MATCH, "\"2\"")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/daily/" + id).header(HttpHeaders.IF_MATCH, "\"2\"")).andExpect(status().isNotFound());
    }
}
