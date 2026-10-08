package com.aicompany.backend.catalog;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 (TD-71): an entry of the shipped catalog -- software, a library entry, a
 * model -- can be retired: it leaves the lists, a restart does not bring it back,
 * and it is restored, as shipped, from the retired entries.
 */
class CatalogRetirementApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogBootstrap bootstrap;

    private final JsonMapper json = JsonMapper.builder().build();
    private String admin;

    @BeforeEach
    void anAdmin() throws Exception {
        admin = AdminSession.bearer(mockMvc);
        jdbc.update("DELETE FROM catalog_retirements");
    }

    @Test
    void aShippedSoftwareEntryIsRetiredStaysRetiredAcrossARestartAndComesBack() throws Exception {
        String tag = mockMvc.perform(get("/api/software/postman")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        try {
            mockMvc.perform(delete("/api/software/postman").header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, tag))
                    .andExpect(status().isNoContent());
            mockMvc.perform(get("/api/software/postman")).andExpect(status().isNotFound());

            bootstrap.run(null);   // what a start does
            mockMvc.perform(get("/api/software/postman")).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/admin/catalog/retirements").header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(jsonPath("$[?(@.key == 'postman')].catalog").value(org.hamcrest.Matchers.contains("SOFTWARE")))
                    .andExpect(jsonPath("$[?(@.key == 'postman')].retiredBy").value(org.hamcrest.Matchers.contains("admin")));
        } finally {
            restore("SOFTWARE", "postman");
        }
        mockMvc.perform(get("/api/software/postman")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/catalog/retirements/SOFTWARE/postman").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void aShippedLibraryEntryIsRetiredAndRestored() throws Exception {
        String tag = "\"" + jdbc.queryForObject("SELECT version FROM harness_resources WHERE key = 'clean-code-java'", Long.class) + "\"";
        try {
            mockMvc.perform(delete("/api/resources/clean-code-java").header(HttpHeaders.AUTHORIZATION, admin)
                    .header(HttpHeaders.IF_MATCH, tag)).andExpect(status().isNoContent());
            bootstrap.run(null);
            assertThat(count("harness_resources", "clean-code-java")).as("not inserted again").isZero();
        } finally {
            restore("RESOURCE", "clean-code-java");
        }
        assertThat(count("harness_resources", "clean-code-java")).isOne();
    }

    @Test
    void aModelIsRetiredByAnAdminOnlyAndRestoredAsShipped() throws Exception {
        String key = "ollama:qwen3.5:4b";
        JsonNode preview = json.readTree(mockMvc.perform(get("/api/catalog/models/deletion").param("key", key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preview.path("allowed").asBoolean()).isTrue();
        assertThat(preview.path("deletes").toString()).contains("congedata");
        assertThat(preview.path("keeps").toString()).contains("non è rimosso da Ollama");

        long version = jdbc.queryForObject("SELECT version FROM llm_models WHERE key = ?", Long.class, key);
        mockMvc.perform(delete("/api/catalog/models").param("key", key).header(HttpHeaders.IF_MATCH, "\"" + version + "\""))
                .andExpect(status().isForbidden());
        try {
            mockMvc.perform(delete("/api/catalog/models").param("key", key).header(HttpHeaders.AUTHORIZATION, admin)
                    .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")).andExpect(status().isNoContent());
            bootstrap.run(null);
            assertThat(count("llm_models", key)).isZero();
            mockMvc.perform(delete("/api/admin/catalog/retirements/MODEL/" + key)).andExpect(status().isForbidden());
        } finally {
            restore("MODEL", key);
        }
        assertThat(count("llm_models", key)).isOne();
    }

    private void restore(String catalog, String key) throws Exception {
        mockMvc.perform(delete("/api/admin/catalog/retirements/" + catalog + "/" + key).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }

    private int count(String table, String key) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE key = ?", Integer.class, key);
        return n == null ? 0 : n;
    }
}
