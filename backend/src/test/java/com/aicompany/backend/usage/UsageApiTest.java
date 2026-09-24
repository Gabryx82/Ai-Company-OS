package com.aicompany.backend.usage;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The usage view over HTTP. The test profile points both local sources at
 * folders that do not exist, so what is asserted is the honesty of the view:
 * no data is reported as no data, and an unanchored weekly window asks for
 * configuration instead of inventing a reset.
 */
class UsageApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyWindowIsReportedWithWhereItsFigureComesFrom() throws Exception {
        mockMvc.perform(get("/api/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", hasItems("claude-code-5h", "claude-code-weekly", "codex-5h",
                        "codex-weekly", "anthropic-api-daily", "openrouter-daily")))
                .andExpect(jsonPath("$[?(@.key == 'codex-5h')].status").value("NO_DATA"))
                .andExpect(jsonPath("$[?(@.key == 'claude-code-5h')].status").value("NO_DATA"))
                .andExpect(jsonPath("$[?(@.key == 'anthropic-api-daily')].nextResetAt", hasItems(notNullValue())));
    }

    @Test
    void aWeeklyWindowWithoutAnAnchorAsksForOneThenCountsFromIt() throws Exception {
        String etag = currentTag();

        mockMvc.perform(put("/api/usage/plans/claude-code-weekly").header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetWeekday\":4,\"resetTime\":\"10:00\",\"resetZone\":\"Europe/Rome\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/usage"))
                .andExpect(jsonPath("$[?(@.key == 'claude-code-weekly')].resetWeekday").value(4))
                .andExpect(jsonPath("$[?(@.key == 'claude-code-weekly')].nextResetAt", hasItems(notNullValue())));
    }

    @Test
    void anAnchorMustBeARealTimeAndZone() throws Exception {
        mockMvc.perform(put("/api/usage/plans/claude-code-weekly").header(HttpHeaders.IF_MATCH, currentTag())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resetWeekday\":4,\"resetTime\":\"25:99\",\"resetZone\":\"Mars/Base\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/usage/plans/nope").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    private String currentTag() throws Exception {
        String body = mockMvc.perform(get("/api/usage")).andReturn().getResponse().getContentAsString();
        int at = body.indexOf("\"key\":\"claude-code-weekly\"");
        int versionAt = body.indexOf("\"version\":", at);
        String version = body.substring(versionAt + 10).split("[,}]")[0];
        return "\"" + version + "\"";
    }
}
