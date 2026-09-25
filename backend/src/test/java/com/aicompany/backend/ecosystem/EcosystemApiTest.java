package com.aicompany.backend.ecosystem;

import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import com.aicompany.backend.support.FakeHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 21 (ADR-028): Ollama, Open WebUI and 3D Omniverse start with AI Company
 * OS -- checked first, never started twice, recorded, and a failure of one never
 * stops the others.
 */
class EcosystemApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeHost host;

    @Autowired
    private EcosystemService ecosystem;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    /** Installed but not running: what the real detector reports for a stopped local service. */
    private static final Detection STOPPED = new Detection(Availability.STOPPED, "not answering", FakeHost.FAKE_EXECUTABLE);

    @BeforeEach
    void theDefaults() {
        host.script("omniverse-3d", STOPPED);
        jdbc.update("DELETE FROM ecosystem_autostart");
        ecosystem.ensureDefaults();
        jdbc.update("DELETE FROM security_events");
    }

    private JsonNode service(String key) throws Exception {
        JsonNode all = json.readTree(mockMvc.perform(get("/api/ecosystem/services")).andReturn().getResponse().getContentAsString());
        for (JsonNode s : all) {
            if (s.path("key").asString().equals(key)) {
                return s;
            }
        }
        throw new AssertionError("no service " + key);
    }

    private String awaitStatus(String key, String wanted) throws Exception {
        String status = null;
        for (int i = 0; i < 300; i++) {
            status = service(key).path("lastStatus").asString();
            if (status.equals(wanted)) {
                return status;
            }
            Thread.sleep(20);
        }
        return status;
    }

    @Test
    void theEcosystemListsOllamaOpenWebUiAndOmniverseToStartInOrder() throws Exception {
        mockMvc.perform(get("/api/ecosystem/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").value(hasItems("ollama", "open-webui", "omniverse-3d")))
                .andExpect(jsonPath("$[0].key").value("ollama"))
                .andExpect(jsonPath("$[?(@.key == 'omniverse-3d')].autostart").value(hasItems(true)))
                .andExpect(jsonPath("$[?(@.key == 'open-webui')].healthUrl").value(hasItems("http://localhost:8080/health")));
    }

    @Test
    void aServiceAlreadyRunningIsNotStartedAgain() throws Exception {
        host.script("open-webui", Detection.of(Availability.RUNNING, "answers"));

        mockMvc.perform(post("/api/ecosystem/services/open-webui/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastStatus").value("ALREADY_RUNNING"));
        assertThat(host.launches()).isEmpty();
    }

    @Test
    void aStoppedServiceIsLaunchedAndBecomesRunningWhenItsHealthAnswers() throws Exception {
        mockMvc.perform(post("/api/ecosystem/services/omniverse-3d/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastStatus").value("STARTING"));
        assertThat(host.launches()).hasSize(1);
        assertThat(host.launches().getFirst().command().toString()).contains("start.ps1");

        host.script("omniverse-3d", Detection.of(Availability.RUNNING, "answers"));
        assertThat(awaitStatus("omniverse-3d", "RUNNING")).isEqualTo("RUNNING");
        assertThat(jdbc.queryForList("SELECT type FROM security_events", String.class)).contains("PROCESS_STARTED");
    }

    @Test
    void oneFailingServiceDoesNotStopTheOthers() throws Exception {
        host.script("ollama", Detection.of(Availability.INCOMPATIBLE_HARDWARE, "no GPU"));
        host.script("open-webui", Detection.of(Availability.RUNNING, "answers"));

        mockMvc.perform(post("/api/ecosystem/start-all")).andExpect(status().isOk());

        assertThat(service("ollama").path("lastStatus").asString()).isEqualTo("FAILED");
        assertThat(service("ollama").path("lastMessage").asString()).contains("not compatible");
        assertThat(service("open-webui").path("lastStatus").asString()).isEqualTo("ALREADY_RUNNING");
        assertThat(service("omniverse-3d").path("lastStatus").asString()).isEqualTo("STARTING");
    }

    @Test
    void aServiceThatNeverAnswersIsReportedAsFailedAfterItsTimeout() throws Exception {
        String admin = AdminSession.bearer(mockMvc);
        long version = service("omniverse-3d").path("version").asLong();
        mockMvc.perform(put("/api/admin/ecosystem/services/omniverse-3d").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autostart\":true,\"position\":3,\"timeoutSeconds\":5}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/ecosystem/services/omniverse-3d/start")).andExpect(status().isOk());
        String outcome = null;
        for (int i = 0; i < 400 && !"FAILED".equals(outcome); i++) {
            outcome = service("omniverse-3d").path("lastStatus").asString();
            Thread.sleep(25);
        }
        assertThat(outcome).isEqualTo("FAILED");
        assertThat(service("omniverse-3d").path("lastMessage").asString()).contains("non risponde dopo 5 s");
    }

    @Test
    void whatStartsWithTheOsIsAnAdminsChoice() throws Exception {
        mockMvc.perform(put("/api/admin/ecosystem/services/open-webui").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"autostart\":false,\"position\":2,\"timeoutSeconds\":60}"))
                .andExpect(status().isForbidden());

        String admin = AdminSession.bearer(mockMvc);
        mockMvc.perform(put("/api/admin/ecosystem/services/open-webui").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"autostart\":false,\"position\":2,\"timeoutSeconds\":60}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/admin/ecosystem/services/open-webui").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"autostart\":false,\"position\":2,\"timeoutSeconds\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autostart").value(false));
    }
}
