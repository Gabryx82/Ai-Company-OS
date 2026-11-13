package com.aicompany.backend.sync;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.support.FakeSyncHttp;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 37 (ADR-043 §4): an agent of AI Company OS as an Open WebUI workspace model,
 * through the API of Open WebUI 0.11 ({@code /api/v1/models}). The operator's key goes
 * to the calls that need it and to no other; a model the operator enriched there keeps
 * what they added; only models with our prefix are written.
 */
class OpenWebUiConnectorTest {

    private static final String KEY = "sk-operator-key";

    private final FakeSyncHttp http = new FakeSyncHttp();
    private final JsonMapper json = JsonMapper.builder().build();
    private final OpenWebUiConnector connector = new OpenWebUiConnector(http, "http://localhost:3000/", KEY);

    private void up() {
        http.answer("GET", "/health", 200, "{\"status\":true}");
        http.answer("GET", "/api/v1/models/list", 200, "{\"items\":[],\"total\":0}");
        http.answer("GET", "/api/v1/models/model", 200, "null");
    }

    private static AgentProfile agent(String model) {
        Agent agent = new Agent("Database Specialist", "Database Engineer", "PostgreSQL", model);
        agent.configureProfile(null, null, "Modella prima i vincoli.", "Schema e migrazioni.", null, null, null, null);
        agent.configureBinding("Disegna lo schema e le migrazioni.", null, model, "engine");
        return AgentProfile.of(agent, List.of(new AgentProfile.Skill("sql-modeling", "Modellazione SQL",
                "Schema normalizzato.", "Ogni vincolo ha un nome.")));
    }

    @Test
    void aNewAgentBecomesAModelOverItsOllamaModelWithItsPromptAsSystem() {
        up();
        http.answer("POST", "/api/v1/models/create", 200, "{\"id\":\"aicos-database-specialist\"}");

        AgentSyncConnector.Published published = connector.publish(agent("ollama:qwen3.5:9b"), null);

        assertThat(published.externalId()).isEqualTo("aicos-database-specialist");
        FakeSyncHttp.Call create = http.calls().stream().filter(c -> c.path().equals("/api/v1/models/create")).findFirst().orElseThrow();
        JsonNode form = json.readTree(create.json());
        assertThat(form.path("id").asString()).isEqualTo("aicos-database-specialist");
        assertThat(form.path("base_model_id").asString()).isEqualTo("qwen3.5:9b");
        assertThat(form.path("name").asString()).isEqualTo("Database Specialist");
        assertThat(form.path("meta").path("description").asString()).isEqualTo("Disegna lo schema e le migrazioni.");
        assertThat(form.path("params").path("system").asString()).contains("Sei Database Specialist")
                .contains("Modella prima i vincoli.").contains("Ogni vincolo ha un nome.");
        assertThat(form.path("is_active").asBoolean()).isTrue();
        assertThat(create.bearer()).isEqualTo(KEY);
    }

    @Test
    void theKeyIsNotSentToTheProbeThatNeedsNone() {
        up();

        assertThat(connector.status().available()).isTrue();

        assertThat(http.calls()).filteredOn(c -> c.path().equals("/health")).isNotEmpty()
                .allSatisfy(c -> assertThat(c.bearer()).isNull());
        assertThat(http.calls()).allSatisfy(c -> assertThat(c.uri().getHost()).isEqualTo("localhost"));
    }

    @Test
    void anUpdateKeepsWhatTheOperatorAddedToTheModelInOpenWebUi() {
        up();
        http.answer("GET", "/api/v1/models/model", 200, """
                {"id":"aicos-database-specialist","name":"Database Specialist","base_model_id":"qwen3.5:9b","is_active":false,
                 "meta":{"description":"vecchia","profile_image_url":"/static/db.png","knowledge":[{"id":"k1"}]},
                 "params":{"system":"vecchio","temperature":0.2},
                 "access_grants":[{"principal_type":"user","principal_id":"*","permission":"read"}]}""");
        http.answer("POST", "/api/v1/models/model/update", 200, "{\"id\":\"aicos-database-specialist\"}");

        connector.publish(agent("ollama:qwen3.5:4b"), "aicos-database-specialist");

        assertThat(http.calls()).noneMatch(c -> c.path().equals("/api/v1/models/create"));
        JsonNode form = json.readTree(http.calls().stream().filter(c -> c.path().equals("/api/v1/models/model/update"))
                .findFirst().orElseThrow().json());
        assertThat(form.path("base_model_id").asString()).isEqualTo("qwen3.5:4b");
        assertThat(form.path("meta").path("description").asString()).isEqualTo("Disegna lo schema e le migrazioni.");
        assertThat(form.path("meta").path("profile_image_url").asString()).isEqualTo("/static/db.png");
        assertThat(form.path("meta").path("knowledge")).hasSize(1);
        assertThat(form.path("params").path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(form.path("params").path("system").asString()).contains("Modella prima i vincoli.").doesNotContain("vecchio");
        assertThat(form.path("access_grants")).hasSize(1);
        assertThat(form.path("is_active").asBoolean()).as("a model the operator switched off stays off").isFalse();
    }

    @Test
    void aModelThatIsNotOllamasCannotRunInOpenWebUi() {
        up();

        assertThat(connector.notApplicable(agent("claude-subscription:default"))).get().asString().contains("Ollama");
        assertThat(connector.notApplicable(agent(null))).isPresent();
        assertThatThrownBy(() -> connector.publish(agent("claude-subscription:default"), null))
                .isInstanceOf(SyncProblemException.class);
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
    }

    @Test
    void whatOpenWebUiRefusesIsSaidInItsWords() {
        up();
        http.answer("POST", "/api/v1/models/create", 401, "{\"detail\":\"Uh-oh! This model id is already registered.\"}");

        assertThatThrownBy(() -> connector.publish(agent("ollama:qwen3.5:9b"), null))
                .isInstanceOf(SyncProblemException.class).hasMessageContaining("already registered")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Modella prima i vincoli").doesNotContain(KEY));
    }

    @Test
    void onlyAModelWithOurPrefixIsWrittenOrRemoved() {
        up();

        assertThatThrownBy(() -> connector.publish(agent("ollama:qwen3.5:9b"), "llama3.2:3b")).isInstanceOf(SyncProblemException.class);
        assertThatThrownBy(() -> connector.remove("llama3.2:3b")).isInstanceOf(SyncProblemException.class);

        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
    }

    @Test
    void removingAsksOpenWebUiOnlyWhenItStillHasTheModel() {
        up();
        connector.remove("aicos-database-specialist");
        assertThat(http.calls()).noneMatch(c -> c.path().equals("/api/v1/models/model/delete"));

        http.answer("GET", "/api/v1/models/model", 200, "{\"id\":\"aicos-database-specialist\"}");
        http.answer("POST", "/api/v1/models/model/delete", 200, "true");
        connector.remove("aicos-database-specialist");

        FakeSyncHttp.Call delete = http.calls().stream().filter(c -> c.path().equals("/api/v1/models/model/delete")).findFirst().orElseThrow();
        assertThat(json.readTree(delete.json()).path("id").asString()).isEqualTo("aicos-database-specialist");
    }

    @Test
    void theListIsReadPageByPageAndSaysWhichModelsAreOurs() {
        http.answer("GET", "/health", 200, "{}");
        http.answer("GET", "/api/v1/models/list?page=", 200, "{\"items\":[]}");
        http.answer("GET", "/api/v1/models/list?page=2", 200, "{\"items\":[{\"id\":\"aicos-b\",\"name\":\"B\",\"meta\":{},\"params\":{}}]}");
        http.answer("GET", "/api/v1/models/list?page=1", 200,
                "{\"items\":[{\"id\":\"my-own\",\"name\":\"Mine\",\"meta\":{\"description\":\"d\"},\"params\":{\"system\":\"s\"}}]}");

        List<AgentSyncConnector.ExternalAgent> models = connector.list();

        assertThat(models).extracting(AgentSyncConnector.ExternalAgent::externalId).containsExactly("my-own", "aicos-b");
        assertThat(models).extracting(AgentSyncConnector.ExternalAgent::ours).containsExactly(false, true);
    }

    @Test
    void theStatusSaysWhatIsMissing() {
        // Nothing answers.
        assertThat(connector.status().availability()).isEqualTo(AgentSyncConnector.Availability.UNREACHABLE);

        // It answers, and there is no key: nothing but the health probe is asked.
        http.answer("GET", "/health", 200, "{}");
        OpenWebUiConnector keyless = new OpenWebUiConnector(http, "http://localhost:3000", " ");
        AgentSyncConnector.Status status = keyless.status();
        assertThat(status.availability()).isEqualTo(AgentSyncConnector.Availability.NOT_CONFIGURED);
        assertThat(status.hint()).contains("AICOS_OPENWEBUI_API_KEY");
        assertThat(keyless.list()).isEmpty();
        assertThat(http.calls()).allSatisfy(c -> assertThat(c.path()).isEqualTo("/health"));

        // The key is not accepted.
        http.answer("GET", "/api/v1/models/list", 401, "{\"detail\":\"Unauthorized\"}");
        assertThat(connector.status().availability()).isEqualTo(AgentSyncConnector.Availability.NOT_CONFIGURED);
    }
}
