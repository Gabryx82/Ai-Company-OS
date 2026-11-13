package com.aicompany.backend.sync;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PHASE 37 (ADR-043): an agent of AI Company OS as an Open WebUI model -- a workspace
 * model that wraps one of Ollama's with the agent's system prompt and description, so
 * that the operator talks to the agent in Open WebUI as it is defined here.
 *
 * <p>Through Open WebUI's own API ({@code /api/v1/models}, read from the 0.11 source),
 * with a key the operator creates there and gives as {@code AICOS_OPENWEBUI_API_KEY}:
 * the control plane never holds a password, and without the key nothing is asked but
 * whether Open WebUI is up. Only models whose id begins with
 * {@link AgentProfile#PREFIX} are written or removed; what the operator added to one
 * in Open WebUI (an image, knowledge, capabilities) is kept across updates.
 */
@Component
public class OpenWebUiConnector implements AgentSyncConnector {

    public static final String KEY = "open-webui";
    private static final int MAX_PAGES = 20;

    private final SyncHttp http;
    private final String base;
    private final String apiKey;
    private final JsonMapper json = JsonMapper.builder().build();

    OpenWebUiConnector(SyncHttp http,
                       @Value("${aicos.sync.open-webui.url:http://localhost:3000}") String url,
                       @Value("${aicos.sync.open-webui.api-key:}") String apiKey) {
        this.http = http;
        this.base = url.strip().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.strip();
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "Open WebUI";
    }

    @Override
    public String shape() {
        return "un modello dell'area di lavoro, con il prompt di sistema dell'agente sopra il suo modello locale";
    }

    @Override
    public Status status() {
        boolean up;
        try {
            up = http.send("GET", uri("/health"), null, null).ok();
        } catch (SyncProblemException e) {
            up = false;
        }
        if (!up) {
            return new Status(Availability.UNREACHABLE, "Open WebUI non risponde su " + base + ".",
                    "Avvialo dal Software Hub, poi torna qui.");
        }
        if (apiKey.isEmpty()) {
            return new Status(Availability.NOT_CONFIGURED, "Open WebUI risponde su " + base + ", ma manca la sua chiave API.",
                    "In Open WebUI: Impostazioni → Account → Chiavi API, crea una chiave. Aggiungi "
                            + "AICOS_OPENWEBUI_API_KEY=<la chiave> a %USERPROFILE%\\.aicos\\local.env e riavvia AI Company OS.");
        }
        SyncHttp.Response probe;
        try {
            probe = http.send("GET", uri("/api/v1/models/list?page=1"), apiKey, null);
        } catch (SyncProblemException e) {
            return new Status(Availability.UNREACHABLE, "Open WebUI ha smesso di rispondere su " + base + ".", "Riprova fra poco.");
        }
        if (probe.status() == 401 || probe.status() == 403) {
            return new Status(Availability.NOT_CONFIGURED, "Open WebUI non accetta la chiave API configurata.",
                    "Controlla che le chiavi API siano abilitate (Pannello di amministrazione → Impostazioni → Generale) "
                            + "e che AICOS_OPENWEBUI_API_KEY in local.env sia quella di un amministratore.");
        }
        if (!probe.ok()) {
            return new Status(Availability.UNREACHABLE, "Open WebUI ha risposto " + probe.status() + " all'elenco dei modelli.",
                    "Riprova fra poco; se continua, guarda i log del container «open-webui».");
        }
        return Status.available("Open WebUI su " + base + ".");
    }

    @Override
    public Optional<String> notApplicable(AgentProfile agent) {
        if (agent.model() == null) {
            return Optional.of("L'agente non ha un modello: Open WebUI ha bisogno di sapere su quale modello locale farlo girare.");
        }
        if (!"ollama".equals(agent.provider()) || agent.modelName() == null) {
            return Optional.of("Il modello dell'agente (" + agent.model() + ") non è un modello locale di Ollama: "
                    + "Open WebUI non lo può eseguire.");
        }
        return Optional.empty();
    }

    @Override
    public String fingerprint(AgentProfile agent) {
        return Fingerprint.of("open-webui/1", agent.name(), agent.modelName(), agent.systemPrompt(), agent.summary());
    }

    @Override
    public Published publish(AgentProfile agent, @Nullable String externalId) {
        requireAvailable();
        notApplicable(agent).ifPresent(why -> {
            throw SyncProblemException.refused(why);
        });
        String id = externalId == null ? agent.slug() : externalId;
        requireOurs(id);
        Optional<JsonNode> existing = find(id);

        ObjectNode form = json.createObjectNode();
        form.put("id", id);
        form.put("base_model_id", agent.modelName());
        form.put("name", agent.name());
        // What the operator added to the model in Open WebUI stays: only the agent's own fields are replaced.
        ObjectNode meta = existing.map(m -> m.path("meta")).filter(JsonNode::isObject)
                .map(m -> (ObjectNode) m.deepCopy()).orElseGet(json::createObjectNode);
        meta.put("description", agent.summary());
        form.set("meta", meta);
        ObjectNode params = existing.map(m -> m.path("params")).filter(JsonNode::isObject)
                .map(m -> (ObjectNode) m.deepCopy()).orElseGet(json::createObjectNode);
        params.put("system", agent.systemPrompt());
        form.set("params", params);
        JsonNode grants = existing.map(m -> m.path("access_grants")).filter(JsonNode::isArray).orElse(null);
        form.set("access_grants", grants == null ? json.createArrayNode() : grants.deepCopy());
        form.put("is_active", existing.map(m -> m.path("is_active").asBoolean(true)).orElse(true));

        String path = existing.isPresent() ? "/api/v1/models/model/update" : "/api/v1/models/create";
        SyncHttp.Response response = http.send("POST", uri(path), apiKey, write(form));
        if (!response.ok()) {
            throw SyncProblemException.refused("Open WebUI non ha " + (existing.isPresent() ? "aggiornato" : "creato")
                    + " il modello «" + id + "»: " + detail(response));
        }
        return new Published(id, fingerprint(agent), true,
                List.of("In Open WebUI lo trovi fra i modelli come «" + agent.name() + "», sopra " + agent.modelName() + "."));
    }

    @Override
    public List<ExternalAgent> list() {
        if (apiKey.isEmpty()) {
            return List.of();
        }
        List<ExternalAgent> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            SyncHttp.Response response = http.send("GET", uri("/api/v1/models/list?page=" + page), apiKey, null);
            if (!response.ok()) {
                throw SyncProblemException.unavailable("Open WebUI non ha elencato i suoi modelli: " + detail(response));
            }
            JsonNode items = read(response.body()).path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                String id = item.path("id").asString("");
                if (id.isBlank()) {
                    continue;
                }
                String description = item.path("meta").path("description").asString("");
                String system = item.path("params").path("system").asString("");
                out.add(new ExternalAgent(id, item.path("name").asString(id), description.isBlank() ? null : description,
                        system.isBlank() ? null : system, id.startsWith(AgentProfile.PREFIX)));
            }
        }
        return out;
    }

    @Override
    public void remove(String externalId) {
        requireAvailable();
        requireOurs(externalId);
        if (find(externalId).isEmpty()) {
            return;
        }
        ObjectNode form = json.createObjectNode();
        form.put("id", externalId);
        SyncHttp.Response response = http.send("POST", uri("/api/v1/models/model/delete"), apiKey, write(form));
        if (!response.ok()) {
            throw SyncProblemException.refused("Open WebUI non ha rimosso il modello «" + externalId + "»: " + detail(response));
        }
    }

    /** The model Open WebUI has under this id, if it has one. */
    private Optional<JsonNode> find(String id) {
        SyncHttp.Response response = http.send("GET",
                uri("/api/v1/models/model?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8)), apiKey, null);
        if (!response.ok()) {
            return Optional.empty();
        }
        JsonNode model = read(response.body());
        return model.isObject() && id.equals(model.path("id").asString("")) ? Optional.of(model) : Optional.empty();
    }

    private void requireAvailable() {
        Status status = status();
        if (!status.available()) {
            throw SyncProblemException.unavailable(status.detail() + (status.hint() == null ? "" : " " + status.hint()));
        }
    }

    private static void requireOurs(String id) {
        if (!id.startsWith(AgentProfile.PREFIX) || id.length() > 256) {
            throw SyncProblemException.refused("'" + id + "' is not a model AI Company OS made in Open WebUI");
        }
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }

    private String write(ObjectNode node) {
        return json.writeValueAsString(node);
    }

    private JsonNode read(String body) {
        try {
            JsonNode node = json.readTree(body == null || body.isBlank() ? "null" : body);
            return node == null ? json.nullNode() : node;
        } catch (JacksonException e) {
            return json.nullNode();
        }
    }

    /** What Open WebUI said: its {@code detail}, or the status. Never the request, which carries the prompt. */
    private String detail(SyncHttp.Response response) {
        JsonNode detail = read(response.body()).path("detail");
        String said;
        if (detail.isArray()) {
            // A validation error: which field, and what is wrong with it -- never the value that was sent.
            List<String> errors = new ArrayList<>();
            for (JsonNode error : detail) {
                List<String> where = new ArrayList<>();
                error.path("loc").forEach(part -> where.add(part.asString(part.toString())));
                errors.add(String.join(".", where) + ": " + error.path("msg").asString("non valido"));
                if (errors.size() == 3) {
                    break;
                }
            }
            said = String.join("; ", errors);
        } else {
            said = detail.asString("");
        }
        return said.isBlank() ? "risposta " + response.status() : said + " (" + response.status() + ")";
    }
}
