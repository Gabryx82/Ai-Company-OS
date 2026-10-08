package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.hub.SkillMarketplaces.Provider;
import com.aicompany.backend.harness.hub.SkillMarketplaces.Result;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The plugin and connector catalogs of the Marketplace (ADR-036 §6b):
 * <ul>
 *   <li>plugin marketplaces kept in a GitHub repository -- Claude's
 *       ({@code .claude-plugin/marketplace.json}: claude-plugins-official,
 *       knowledge-work-plugins) and Codex's ({@code .agents/plugins/marketplace.json}:
 *       openai/plugins);</li>
 *   <li>the official MCP Registry ({@code registry.modelcontextprotocol.io}) for
 *       connectors: remote servers (an HTTPS URL) and local ones (an npm, PyPI or
 *       container package started by the application).</li>
 * </ul>
 * What a connector needs to run -- an API key in a header, a variable in its
 * environment -- is read from the registry and asked of the operator at install
 * time; the Hub never invents it and never stores it.
 */
@Service
public class ExtensionMarketplaces {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_JSON = 6 * 1024 * 1024;
    static final Pattern SERVER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,100}/[A-Za-z0-9][A-Za-z0-9._-]{0,100}$");
    static final Pattern PACKAGE = Pattern.compile("^@?[A-Za-z0-9][A-Za-z0-9._/:-]{0,213}$");
    static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$");
    static final Pattern VARIABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]{0,63}$");
    /** An HTTPS endpoint with nothing left to fill in and nothing a command line would read as syntax. */
    static final Pattern REMOTE_URL = Pattern.compile("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~:/@!$'()*+,;=-]*)?$");

    public enum Kind { SKILL, PLUGIN, CONNECTOR }

    /** One plugin of a marketplace, enough to install it with the application's CLI. */
    public record PluginEntry(String provider, String name, String marketplace, String repo, String description,
                              String category, String author, String homepage, String version, String sourceUrl) {
    }

    /** Something the operator gives a connector: a header or an environment variable. */
    public record ConnectorInput(String name, String kind, boolean required, boolean secret, @Nullable String description,
                        @Nullable String defaultValue) {
    }

    /**
     * How a connector runs: remote ({@code transport} http or sse, {@code url}) or
     * local ({@code stdio}, the {@code command} that starts it).
     */
    public record ConnectorSpec(String provider, String ref, String name, String title, String description, String version,
                                String repository, String transport, String url, List<String> command,
                                String packageType, List<ConnectorInput> inputs, List<String> warnings) {
        public boolean remote() {
            return url != null;
        }
    }

    private record Cached(Instant at, JsonNode body) {
    }

    private final HubWeb web;
    private final Map<String, Cached> catalogs = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> manifests = new ConcurrentHashMap<>();
    /** The registry's answers and the servers seen in them, for {@link #TTL}: a preview after a search asks nothing again. */
    private final Map<String, Cached> registry = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "skill-hub-catalog");
        t.setDaemon(true);
        return t;
    });

    public ExtensionMarketplaces(HubWeb web) {
        this.web = web;
    }

    void clearCaches() {
        catalogs.clear();
        manifests.clear();
        registry.clear();
    }

    // --- search ----------------------------------------------------------------------------

    public List<Result> search(Provider provider, String query) {
        return switch (provider.api()) {
            case CLAUDE_MARKETPLACE, CODEX_MARKETPLACE -> plugins(provider, query);
            case MCP_REGISTRY -> servers(provider, query);
            default -> List.of();
        };
    }

    private List<Result> plugins(Provider provider, String query) {
        String needle = query == null ? null : query.toLowerCase(Locale.ROOT);
        List<PluginEntry> matching = new ArrayList<>();
        for (PluginEntry entry : catalog(provider)) {
            String text = (entry.name() + " " + Optional.ofNullable(entry.description()).orElse("") + " "
                    + Optional.ofNullable(entry.category()).orElse("")).toLowerCase(Locale.ROOT);
            if (needle == null || text.contains(needle)) {
                matching.add(entry);
            }
        }
        List<PluginEntry> shown = matching.stream().limit(needle == null ? 120 : 60).toList();
        // Codex's catalog names its plugins and nothing else: their manifests say what they are.
        Map<String, Future<JsonNode>> extra = new LinkedHashMap<>();
        if (provider.api() == SkillMarketplaces.Api.CODEX_MARKETPLACE) {
            for (PluginEntry entry : shown) {
                if (entry.description() == null) {
                    extra.put(entry.name(), pool.submit(() -> codexManifest(provider, entry.name())));
                }
            }
        }
        List<Result> out = new ArrayList<>();
        for (PluginEntry entry : shown) {
            String description = entry.description();
            String name = entry.name();
            Future<JsonNode> manifest = extra.get(entry.name());
            if (manifest != null) {
                JsonNode m = await(manifest);
                if (m != null) {
                    description = Optional.ofNullable(text(m.path("interface"), "shortDescription")).orElse(text(m, "description"));
                    name = Optional.ofNullable(text(m.path("interface"), "displayName")).orElse(name);
                }
            }
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Marketplace", entry.marketplace());
            if (entry.category() != null) {
                details.put("Categoria", entry.category());
            }
            out.add(new Result(provider.key(), entry.name(), name, description, entry.author(), provider.repo(), null,
                    entry.version(), Optional.ofNullable(entry.homepage()).orElse(entry.sourceUrl()), false,
                    Kind.PLUGIN.name(), provider.targets(), details));
        }
        return out;
    }

    private List<Result> servers(Provider provider, String query) {
        JsonNode body = registrySearch(query == null || query.isBlank() ? null : query.strip());
        List<Result> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode item : body.path("servers")) {
            JsonNode server = item.path("server");
            String name = text(server, "name");
            if (name == null || !SERVER_NAME.matcher(name).matches() || !seen.add(name)) {
                continue;
            }
            registry.put("server:" + name, new Cached(Instant.now(), server));
            ConnectorSpec spec = spec(provider, server);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Tipo", spec.transport() == null ? "non installabile da qui" : spec.remote() ? "remoto (" + spec.transport() + ")" : "locale");
            if (spec.url() != null) {
                details.put("Indirizzo", SkillScanner.safeUrl(spec.url()));
            }
            if (spec.packageType() != null) {
                details.put("Pacchetto", spec.packageType() + " · " + String.join(" ", spec.command()));
            }
            List<String> required = spec.inputs().stream().filter(ConnectorInput::required).map(ConnectorInput::name).toList();
            if (!required.isEmpty()) {
                details.put("Richiede", String.join(", ", required));
            }
            out.add(new Result(provider.key(), name, Optional.ofNullable(text(server, "title")).orElse(shortName(name)),
                    text(server, "description"), name.substring(0, name.indexOf('/')), spec.repository(), null,
                    text(server, "version"), Optional.ofNullable(text(server, "websiteUrl")).orElse(spec.repository()),
                    false, Kind.CONNECTOR.name(), spec.transport() == null ? List.of() : provider.targets(), details));
        }
        return out;
    }

    // --- one item -----------------------------------------------------------------------------

    public PluginEntry plugin(Provider provider, String name) {
        if (name == null || !SkillMarketplaces.SLUG.matcher(name).matches()) {
            throw HubProblemException.notFound("Not a plugin name: " + name);
        }
        return catalog(provider).stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> HubProblemException.notFound("Il plugin «" + name + "» non è nel marketplace " + provider.name()));
    }

    /** A plugin's own manifest, when its source is a folder of the marketplace's repository. */
    public Optional<JsonNode> pluginManifest(Provider provider, PluginEntry entry) {
        if (provider.api() == SkillMarketplaces.Api.CODEX_MARKETPLACE) {
            return Optional.ofNullable(codexManifest(provider, entry.name()));
        }
        return Optional.ofNullable(manifest("claude:" + provider.repo() + ":" + entry.name(), () -> {
            JsonNode catalog = catalogJson(provider);
            for (JsonNode p : catalog.path("plugins")) {
                if (entry.name().equals(text(p, "name"))) {
                    String source = text(p, "source");
                    if (source != null && source.startsWith("./") && PackagePaths.valid(source.substring(2))) {
                        return fetchJson(raw(provider.repo(), source.substring(2) + "/.claude-plugin/plugin.json"));
                    }
                }
            }
            return null;
        }));
    }

    public ConnectorSpec connector(Provider provider, String ref) {
        if (ref == null || !SERVER_NAME.matcher(ref).matches()) {
            throw HubProblemException.notFound("Not a connector of the MCP Registry: " + ref);
        }
        Cached seen = registry.get("server:" + ref);
        if (seen != null && seen.at().plus(TTL).isAfter(Instant.now())) {
            return spec(provider, seen.body());
        }
        JsonNode body = registrySearch(ref);
        for (JsonNode item : body.path("servers")) {
            if (ref.equals(text(item.path("server"), "name"))) {
                return spec(provider, item.path("server"));
            }
        }
        throw HubProblemException.notFound("Il registro MCP non ha «" + ref + "»");
    }

    /**
     * How the Hub would install a server: a remote endpoint when there is one
     * (streamable HTTP before SSE) without variables in its URL; otherwise an npm,
     * PyPI or container package started over stdio. What it needs from the
     * operator comes along; what the Hub cannot fill (positional arguments,
     * URL variables) makes it not installable from here, and says so.
     */
    ConnectorSpec spec(Provider provider, JsonNode server) {
        String name = text(server, "name");
        String repository = text(server.path("repository"), "url");
        List<String> warnings = new ArrayList<>();
        for (String preferred : List.of("streamable-http", "sse")) {
            for (JsonNode remote : server.path("remotes")) {
                String type = text(remote, "type");
                String url = text(remote, "url");
                if (!preferred.equals(type) || url == null) {
                    continue;
                }
                if (!REMOTE_URL.matcher(url).matches()) {
                    warnings.add("L'indirizzo remoto ha parti da completare o caratteri speciali: va configurato a mano");
                    continue;
                }
                List<ConnectorInput> inputs = new ArrayList<>();
                for (JsonNode header : remote.path("headers")) {
                    String headerName = text(header, "name");
                    if (headerName != null && headerName.matches("[A-Za-z0-9-]{1,64}")) {
                        inputs.add(new ConnectorInput(headerName, "HEADER", header.path("isRequired").asBoolean(false),
                                header.path("isSecret").asBoolean(false), text(header, "description"), null));
                    }
                }
                return new ConnectorSpec(provider.key(), name, shortName(name), text(server, "title"),
                        text(server, "description"), text(server, "version"), repository,
                        preferred.equals("sse") ? "sse" : "http", url, List.of(), null, inputs, warnings);
            }
        }
        for (JsonNode pkg : server.path("packages")) {
            String type = Optional.ofNullable(text(pkg, "registryType")).orElse("").toLowerCase(Locale.ROOT);
            String identifier = text(pkg, "identifier");
            String version = text(pkg, "version");
            String transport = text(pkg.path("transport"), "type");
            if (identifier == null || !PACKAGE.matcher(identifier).matches() || (transport != null && !"stdio".equals(transport))) {
                continue;
            }
            if (version != null && !VERSION.matcher(version).matches()) {
                version = null;
            }
            boolean requiredArgs = false;
            for (JsonNode argument : pkg.path("packageArguments")) {
                requiredArgs |= argument.path("isRequired").asBoolean(false) && text(argument, "default") == null
                        && text(argument, "value") == null;
            }
            if (requiredArgs) {
                warnings.add("Il pacchetto " + identifier + " vuole argomenti da riga di comando: va configurato a mano");
                continue;
            }
            List<String> command = switch (type) {
                case "npm" -> List.of("npx", "-y", version == null ? identifier : identifier + "@" + version);
                case "pypi" -> List.of("uvx", version == null ? identifier : identifier + "==" + version);
                case "oci" -> List.of("docker", "run", "-i", "--rm", version == null || identifier.contains(":") ? identifier
                        : identifier + ":" + version);
                default -> null;
            };
            if (command == null) {
                continue;
            }
            List<ConnectorInput> inputs = new ArrayList<>();
            for (JsonNode variable : pkg.path("environmentVariables")) {
                String variableName = text(variable, "name");
                if (variableName != null && VARIABLE.matcher(variableName).matches()) {
                    inputs.add(new ConnectorInput(variableName, "ENV", variable.path("isRequired").asBoolean(false),
                            variable.path("isSecret").asBoolean(false), text(variable, "description"), text(variable, "default")));
                }
            }
            if ("oci".equals(type)) {
                warnings.add("Parte in un container: serve Docker avviato");
            }
            return new ConnectorSpec(provider.key(), name, shortName(name), text(server, "title"), text(server, "description"),
                    text(server, "version"), repository, "stdio", null, command, type, inputs, warnings);
        }
        warnings.add("Nessun modo di avvio che il Knowledge Hub sappia configurare");
        return new ConnectorSpec(provider.key(), name, shortName(name), text(server, "title"), text(server, "description"),
                text(server, "version"), repository, null, null, List.of(), null, List.of(), warnings);
    }

    private JsonNode registrySearch(String query) {
        String key = "search:" + (query == null ? "" : query.toLowerCase(Locale.ROOT));
        Cached cached = registry.get(key);
        if (cached != null && cached.at().plus(TTL).isAfter(Instant.now())) {
            return cached.body();
        }
        JsonNode body = json(URI.create("https://registry.modelcontextprotocol.io/v0/servers?limit=40&version=latest"
                + (query == null ? "" : "&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8))));
        registry.put(key, new Cached(Instant.now(), body));
        return body;
    }

    // --- catalogs ---------------------------------------------------------------------------

    private List<PluginEntry> catalog(Provider provider) {
        JsonNode catalog = catalogJson(provider);
        String marketplace = Optional.ofNullable(provider.marketplace()).orElse(text(catalog, "name"));
        List<PluginEntry> out = new ArrayList<>();
        for (JsonNode p : catalog.path("plugins")) {
            String name = text(p, "name");
            if (name == null || !SkillMarketplaces.SLUG.matcher(name).matches()) {
                continue;
            }
            String author = Optional.ofNullable(text(p.path("author"), "name")).orElse(text(p, "author"));
            String source = text(p, "source");
            String sourceUrl = source != null && source.startsWith("./")
                    ? "https://github.com/" + provider.repo() + "/tree/HEAD/" + source.substring(2)
                    : Optional.ofNullable(text(p.path("source"), "url")).orElse(text(p.path("source"), "path"));
            if (sourceUrl != null && sourceUrl.startsWith("./")) {
                sourceUrl = "https://github.com/" + provider.repo() + "/tree/HEAD/" + sourceUrl.substring(2);
            }
            out.add(new PluginEntry(provider.key(), name, marketplace, provider.repo(), text(p, "description"),
                    text(p, "category"), author, text(p, "homepage"), text(p, "version"), sourceUrl));
        }
        return out;
    }

    private JsonNode catalogJson(Provider provider) {
        Cached cached = catalogs.get(provider.key());
        if (cached != null && cached.at().plus(TTL).isAfter(Instant.now())) {
            return cached.body();
        }
        String path = provider.api() == SkillMarketplaces.Api.CODEX_MARKETPLACE
                ? ".agents/plugins/marketplace.json" : ".claude-plugin/marketplace.json";
        JsonNode body = fetchJson(raw(provider.repo(), path));
        if (body == null) {
            throw HubProblemException.marketplace("Il catalogo di " + provider.name() + " non risponde");
        }
        catalogs.put(provider.key(), new Cached(Instant.now(), body));
        return body;
    }

    private JsonNode codexManifest(Provider provider, String name) {
        return manifest("codex:" + provider.repo() + ":" + name,
                () -> fetchJson(raw(provider.repo(), "plugins/" + name + "/.codex-plugin/plugin.json")));
    }

    /** Kept once read -- fetched outside the map, so that parallel reads never wait on each other. */
    private JsonNode manifest(String key, java.util.function.Supplier<JsonNode> fetch) {
        JsonNode known = manifests.get(key);
        if (known != null) {
            return known;
        }
        JsonNode fetched = fetch.get();
        if (fetched != null) {
            manifests.putIfAbsent(key, fetched);
        }
        return fetched;
    }

    // --- helpers ---------------------------------------------------------------------------

    private JsonNode fetchJson(URI uri) {
        try {
            HubWeb.Response response = web.get(uri, MAX_JSON);
            return response.ok() ? JSON.readTree(response.text()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JsonNode json(URI uri) {
        HubWeb.Response response = web.get(uri, MAX_JSON);
        if (response.status() == 429) {
            throw HubProblemException.marketplace(uri.getHost() + " ha limitato le richieste: riprova fra poco");
        }
        if (!response.ok()) {
            throw HubProblemException.marketplace(uri.getHost() + " answered HTTP " + response.status());
        }
        try {
            return JSON.readTree(response.text());
        } catch (RuntimeException e) {
            throw HubProblemException.marketplace("The answer is not JSON");
        }
    }

    private static URI raw(String repo, String path) {
        try {
            return new URI("https", "raw.githubusercontent.com", "/" + repo + "/HEAD/" + path, null);
        } catch (URISyntaxException e) {
            throw HubProblemException.packageRefused("Not a valid path: " + path);
        }
    }

    /** {@code io.github.owner/my-server} → {@code my-server}, a name the applications accept. */
    static String shortName(String registryName) {
        String last = registryName.substring(registryName.indexOf('/') + 1);
        if (last.toLowerCase(Locale.ROOT).matches("mcp|server|mcp-server|mcpserver|remote")) {
            // com.notion/mcp → notion: the publisher says more than a generic last part.
            String namespace = registryName.substring(0, registryName.indexOf('/'));
            last = namespace.substring(namespace.lastIndexOf('.') + 1);
        }
        String clean = last.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("(^-+|-+$)", "");
        return clean.isEmpty() ? "mcp-server" : clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static JsonNode await(Future<JsonNode> future) {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.isContainer() ? null : value.asString();
    }
}
