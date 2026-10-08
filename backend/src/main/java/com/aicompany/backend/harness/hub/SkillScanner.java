package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.hub.SkillLocations.Location;
import com.aicompany.backend.harness.hub.SkillLocations.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads every application's skills, plugins, connectors and knowledge from disk
 * (ADR-036 §2), with the layout each one uses: personal folders, plugin trees,
 * Codex's plugin cache, Claude Code's plugin store, Claude Desktop's per-account
 * manifests, MCP configurations, global instruction files, knowledge items and
 * Claude Code's project memories.
 *
 * <p>Read-only. Nothing below a location's root is ever followed through a link,
 * and a walk stops at {@value #MAX_DEPTH} levels and {@value #MAX_PER_LOCATION}
 * items per location. A {@code SKILL.md} whose size and modification time did
 * not change since the previous scan is not read again.
 *
 * <p><strong>A connector's secrets never leave its configuration.</strong> Of an MCP
 * server the scanner keeps its name, its transport, the executable's name or the
 * URL's host and path -- never its arguments, its environment, its headers or a
 * query string, which is where API keys live.
 */
@Component
public class SkillScanner {

    private static final Logger LOG = LoggerFactory.getLogger(SkillScanner.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final int MAX_DEPTH = 6;
    static final int MAX_PER_LOCATION = 3000;
    private static final int MAX_SKILL_MD = 512 * 1024;
    private static final Set<String> SKIPPED = Set.of("node_modules", "__pycache__", ".git", ".venv", "venv",
            "site-packages", ".tmp", "tmp");
    private static final List<String> PLUGIN_MANIFESTS = List.of(".claude-plugin/plugin.json", ".codex-plugin/plugin.json",
            "plugin.json", "gemini-extension.json");

    static final SkillCategories.Category INSTRUCTIONS = new SkillCategories.Category("istruzioni", "Istruzioni e knowledge");
    static final SkillCategories.Category CONNECTORS = new SkillCategories.Category("connettori", "Connettori e servizi");

    /** One scan: what was found, when, and what the scan could not read or chose to leave out. */
    public record Scan(Instant at, List<FoundSkill> skills, List<String> notes) {
    }

    private record Cached(long size, long modified, String sha, Map<String, String> frontmatter) {
    }

    private record Plugin(String name, String version, String marketplace, Boolean enabled) {
    }

    /** An MCP server as one source declares it, before the application's connectors are merged by name. */
    private record RawConnector(String name, String transport, String target, String source, Scope scope, Boolean enabled,
                                String version, String tools, String description) {
        RawConnector(String name, String transport, String target, String source, Scope scope, Boolean enabled) {
            this(name, transport, target, source, scope, enabled, null, null, null);
        }

        RawConnector(String name, String transport, String target, String source, Scope scope, Boolean enabled, String version) {
            this(name, transport, target, source, scope, enabled, version, null, null);
        }
    }

    /** What one scan carries from location to location. */
    private static final class State {
        final Set<Path> seen = ConcurrentHashMap.newKeySet();
        final Map<String, List<RawConnector>> connectors = new HashMap<>();
        final List<String> notes = new ArrayList<>();

        void connector(String app, RawConnector connector) {
            connectors.computeIfAbsent(app, a -> new ArrayList<>()).add(connector);
        }
    }

    private final SkillLocations locations;
    private final Map<Path, Cached> cache = new ConcurrentHashMap<>();
    private volatile Scan last;

    public SkillScanner(SkillLocations locations) {
        this.locations = locations;
    }

    /** The latest scan, made now when there is none yet. */
    public Scan last() {
        Scan scan = last;
        return scan != null ? scan : scan();
    }

    public Optional<FoundSkill> find(String id) {
        Optional<FoundSkill> found = last().skills().stream().filter(s -> s.id().equals(id)).findFirst();
        return found.isPresent() ? found : scan().skills().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** Reads every location now -- the connectors last, once every plugin has said which it brings. One scan at a time. */
    public synchronized Scan scan() {
        List<FoundSkill> out = new ArrayList<>();
        State state = new State();
        List<Location> all = locations.locations();
        List<Location> ordered = new ArrayList<>(all.stream().filter(l -> l.layout() != SkillLocations.Layout.CONNECTORS).toList());
        ordered.addAll(all.stream().filter(l -> l.layout() == SkillLocations.Layout.CONNECTORS).toList());
        for (Location location : ordered) {
            try {
                List<FoundSkill> found = switch (location.layout()) {
                    case SKILL_FOLDERS -> skillFolders(location, state);
                    case PLUGIN_SKILLS -> pluginSkills(location, state);
                    case CODEX_PLUGINS -> codexPlugins(location, state);
                    case CLAUDE_CODE_PLUGINS -> claudeCodePlugins(location, state);
                    case CLAUDE_DESKTOP -> claudeDesktop(location, state);
                    case SINGLE_FILE -> singleFile(location);
                    case KNOWLEDGE_ITEMS -> knowledgeItems(location, state);
                    case CLAUDE_CODE_MEMORY -> memories(location, state);
                    case CONNECTORS -> connectors(location, state);
                };
                if (found.size() > MAX_PER_LOCATION) {
                    state.notes.add(location.label() + ": mostrati i primi " + MAX_PER_LOCATION + " di " + found.size());
                    found = found.subList(0, MAX_PER_LOCATION);
                }
                out.addAll(found);
            } catch (RuntimeException e) {
                LOG.warn("Skill location {} could not be read", location.id(), e);
                state.notes.add(location.label() + " (" + location.app() + "): non leggibile -- " + e.getMessage());
            }
        }
        cache.keySet().retainAll(state.seen);
        Scan scan = new Scan(Instant.now(), List.copyOf(out), List.copyOf(state.notes));
        last = scan;
        return scan;
    }

    // --- layouts ----------------------------------------------------------------------

    /** {@code <root>/<name>/SKILL.md}: each folder with a SKILL.md is a skill. */
    private List<FoundSkill> skillFolders(Location location, State state) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path folder : children(location.root())) {
            skillMd(folder).flatMap(md -> skill(location, location.root(), folder, md, location.scope(), null, state))
                    .ifPresent(out::add);
        }
        return out;
    }

    /** {@code <root>/<plugin>/...}: each plugin, and every SKILL.md below it. */
    private List<FoundSkill> pluginSkills(Location location, State state) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path pluginFolder : children(location.root())) {
            Plugin plugin = pluginOf(pluginFolder, pluginFolder.getFileName().toString(), null, null, null);
            out.add(pluginItem(location, pluginFolder, plugin, state));
            out.addAll(skillsBelow(location, pluginFolder, plugin, state));
        }
        return out;
    }

    /** {@code <cache>/<marketplace>/<plugin>/<version>/}: the newest version folder of each plugin. */
    private List<FoundSkill> codexPlugins(Location location, State state) {
        List<FoundSkill> out = new ArrayList<>();
        Map<String, Boolean> enabled = codexEnabled(location.root().getParent().getParent().resolve("config.toml"));
        for (Path marketplace : children(location.root())) {
            for (Path pluginFolder : children(marketplace)) {
                Optional<Path> newest = children(pluginFolder).stream().max(Comparator.comparing(SkillScanner::modifiedMillis));
                if (newest.isEmpty()) {
                    continue;
                }
                String name = pluginFolder.getFileName().toString();
                String market = marketplace.getFileName().toString();
                Plugin plugin = pluginOf(newest.get(), name, newest.get().getFileName().toString(), market,
                        enabled.get(name + "@" + market));
                out.add(pluginItem(location, newest.get(), plugin, state));
                out.addAll(skillsBelow(location, newest.get(), plugin, state));
            }
        }
        return out;
    }

    /**
     * Claude Code's plugins: those in {@code installed_plugins.json} -- each install
     * path must stay inside the plugin store -- and those {@code settings.json}
     * enables straight from a marketplace's local copy. Enabled or not, from
     * {@code enabledPlugins}.
     */
    private List<FoundSkill> claudeCodePlugins(Location location, State state) {
        List<FoundSkill> out = new ArrayList<>();
        Map<String, Boolean> enabled = new LinkedHashMap<>();
        json(location.root().getParent().resolve("settings.json")).ifPresent(settings ->
                settings.path("enabledPlugins").properties().forEach(e -> enabled.put(e.getKey(), e.getValue().asBoolean(false))));
        Map<String, Path> folders = new LinkedHashMap<>();
        Map<String, String> versions = new HashMap<>();
        json(location.root().resolve("installed_plugins.json")).ifPresent(installed -> {
            JsonNode plugins = installed.has("plugins") ? installed.path("plugins") : installed;
            for (Map.Entry<String, JsonNode> entry : plugins.properties()) {
                JsonNode install = entry.getValue().isArray() ? entry.getValue().path(0) : entry.getValue();
                String path = text(install, "installPath");
                if (path == null) {
                    continue;
                }
                Path folder = Path.of(path).toAbsolutePath().normalize();
                if (folder.startsWith(location.root()) && Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                    folders.put(entry.getKey(), folder);
                    versions.put(entry.getKey(), text(install, "version"));
                }
            }
        });
        // Enabled without an install record: the plugin lives in its marketplace's local copy.
        for (String key : enabled.keySet()) {
            String[] nameAt = key.split("@", 2);
            if (folders.containsKey(key) || nameAt.length < 2 || !SkillMarketplaces.OWNER.matcher(nameAt[1]).matches()
                    || !SkillMarketplaces.SLUG.matcher(nameAt[0]).matches()) {
                continue;
            }
            Path marketplace = location.root().resolve("marketplaces").resolve(nameAt[1]);
            for (String sub : List.of("plugins", "external_plugins")) {
                Path folder = marketplace.resolve(sub).resolve(nameAt[0]);
                if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                    folders.put(key, folder);
                    break;
                }
            }
        }
        for (Map.Entry<String, Path> entry : folders.entrySet()) {
            String[] nameAt = entry.getKey().split("@", 2);
            Plugin plugin = pluginOf(entry.getValue(), nameAt[0], versions.get(entry.getKey()),
                    nameAt.length > 1 ? nameAt[1] : null, enabled.getOrDefault(entry.getKey(), true));
            plugin = new Plugin(nameAt[0], plugin.version(), plugin.marketplace(), plugin.enabled());
            out.add(pluginItem(location, entry.getValue(), plugin, state));
            out.addAll(skillsBelow(location, entry.getValue(), plugin, state));
        }
        out.addAll(builtinPlugins(location, enabled));
        return out;
    }

    /**
     * PHASE 32 (TD-84): the plugins built into Claude Code ({@code name@builtin}) have
     * no folder and no manifest. Claude Code names them where it counts their use
     * ({@code pluginUsage} in {@code ~/.claude.json}) and where they are switched on or
     * off ({@code enabledPlugins}); only the names, the use and the switch are read.
     */
    private List<FoundSkill> builtinPlugins(Location location, Map<String, Boolean> enabled) {
        Map<String, JsonNode> usage = new java.util.TreeMap<>();
        json(locations.home().resolve(".claude.json")).ifPresent(config ->
                config.path("pluginUsage").properties().forEach(e -> usage.put(e.getKey(), e.getValue())));
        Set<String> keys = new java.util.TreeSet<>(usage.keySet());
        keys.addAll(enabled.keySet());
        List<FoundSkill> out = new ArrayList<>();
        for (String key : keys) {
            String name = key.endsWith("@builtin") ? key.substring(0, key.length() - "@builtin".length()) : null;
            if (name == null || !SkillMarketplaces.SLUG.matcher(name).matches()) {
                continue;
            }
            Boolean on = enabled.get(key);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Tipo", "interno di Claude Code (@builtin)");
            JsonNode used = usage.get(key);
            if (used != null && used.path("usageCount").isNumber()) {
                details.put("Usato", used.path("usageCount").asLong() + " volte");
            }
            if (on != null) {
                details.put("Stato", on ? "attivo" : "disattivato");
            }
            String description = "Plugin interno di Claude Code: arriva con l'app, senza cartella né manifest.";
            String relative = FoundSkill.BUILTIN + name;
            Path nowhere = location.root().resolve("@builtin").resolve(name);
            Instant last = used != null && used.path("lastUsedAt").isNumber()
                    ? Instant.ofEpochMilli(used.path("lastUsedAt").asLong()) : null;
            out.add(new FoundSkill(FoundSkill.idOf(location.id(), relative), location.app(), location.id(), Scope.PLUGIN,
                    FoundSkill.PLUGIN, name, description, SkillCategories.of(name, null, null), name, null, "builtin", on, null,
                    nowhere, nowhere, relative, false, Fingerprints.sha256(key + ":" + on), last, null,
                    Collections.unmodifiableMap(details)));
        }
        return out;
    }

    /**
     * Claude Desktop keeps, per account, a manifest of its skills
     * ({@code skills-plugin/<org>/<account>/manifest.json}) and one of its plugins
     * ({@code <account>/<org>/rpm/manifest.json}). Only the account updated last is
     * the one in use; the others are counted, not listed.
     */
    private List<FoundSkill> claudeDesktop(Location location, State state) {
        List<FoundSkill> out = new ArrayList<>();
        Path root = location.root();
        List<Path> skillManifests = new ArrayList<>();
        for (Path org : children(root.resolve("skills-plugin"))) {
            for (Path account : children(org)) {
                if (Files.isRegularFile(account.resolve("manifest.json"))) {
                    skillManifests.add(account.resolve("manifest.json"));
                }
            }
        }
        List<Path> pluginManifests = new ArrayList<>();
        for (Path account : children(root)) {
            if (account.getFileName().toString().equals("skills-plugin")) {
                continue;
            }
            for (Path org : children(account)) {
                if (Files.isRegularFile(org.resolve("rpm").resolve("manifest.json"))) {
                    pluginManifests.add(org.resolve("rpm").resolve("manifest.json"));
                }
            }
        }
        Optional<Path> skills = newest(skillManifests);
        if (skillManifests.size() > 1) {
            state.notes.add("Claude Desktop: " + (skillManifests.size() - 1) + " altro account trovato, non in uso: non elencato");
        }
        skills.flatMap(SkillScanner::json).ifPresent(manifest -> {
            Path skillsRoot = skills.get().getParent().resolve("skills");
            for (JsonNode entry : manifest.path("skills")) {
                String id = text(entry, "skillId");
                if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
                    continue;
                }
                // Anthropic's skills live in a folder named by their id; the ones the user uploads have an
                // id like "skill_01X4…" and a folder named by their name.
                Path folder = skillsRoot.resolve(id);
                String name = text(entry, "name");
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) && name != null && name.matches("[A-Za-z0-9._-]+")) {
                    folder = skillsRoot.resolve(name);
                }
                Path skillFolder = folder;
                Scope scope = "anthropic".equalsIgnoreCase(text(entry, "creatorType")) ? Scope.SYSTEM : Scope.PERSONAL;
                Boolean on = entry.has("enabled") ? entry.path("enabled").asBoolean(true) : null;
                Plugin owner = new Plugin(null, null, scope == Scope.SYSTEM ? "anthropic" : null, on);
                skillMd(skillFolder).flatMap(md -> skill(location, root, skillFolder, md, scope, owner, state)).ifPresent(out::add);
            }
        });
        Optional<Path> plugins = newest(pluginManifests);
        plugins.flatMap(SkillScanner::json).ifPresent(manifest -> {
            Path rpm = plugins.get().getParent();
            for (JsonNode entry : manifest.path("plugins")) {
                String id = text(entry, "id");
                if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
                    continue;
                }
                Path folder = rpm.resolve(id);
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = Optional.ofNullable(text(entry, "name")).orElse(id);
                Plugin plugin = pluginOf(folder, name, null, text(entry, "marketplaceName"), true);
                plugin = new Plugin(name, plugin.version(), plugin.marketplace(), true);
                out.add(pluginItem(location, folder, plugin, state));
                out.addAll(skillsBelow(location, folder, plugin, state));
            }
        });
        return out;
    }

    private List<FoundSkill> singleFile(Location location) {
        Path file = location.root();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            return List.of(new FoundSkill(FoundSkill.idOf(location.id(), name), location.app(), location.id(),
                    location.scope(), FoundSkill.KNOWLEDGE, name, firstLine(text), INSTRUCTIONS, null, null, null, null, null,
                    file, file.getParent(), name, false, Fingerprints.sha256(text), modified(file), null,
                    Map.of("File", file.toString())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<FoundSkill> knowledgeItems(Location location, State state) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path folder : children(location.root())) {
            Optional<JsonNode> metadata = json(folder.resolve("metadata.json"));
            List<Path> markdown = Fingerprints.files(folder).stream()
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList();
            Path file = markdown.isEmpty() ? folder.resolve("metadata.json") : markdown.getFirst();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            state.seen.add(file);
            String name = metadata.map(m -> text(m, "title")).orElse(folder.getFileName().toString());
            String description = metadata.map(m -> text(m, "summary")).orElse(null);
            String relative = Fingerprints.relative(location.root(), folder);
            out.add(new FoundSkill(FoundSkill.idOf(location.id(), relative), location.app(), location.id(),
                    Scope.KNOWLEDGE, FoundSkill.KNOWLEDGE, name, description, INSTRUCTIONS, null, null, null, null, null,
                    file, folder, relative, false, Fingerprints.quick(folder, ""), modified(file), null,
                    Map.of("Cartella", folder.toString())));
        }
        return out;
    }

    /**
     * {@code ~/.claude/projects/<project>/memory}: one item per project, its
     * {@code MEMORY.md} the index of what Claude Code remembers there. The project's
     * own path comes from {@code ~/.claude.json}, whose keys encode to the folder names.
     */
    private List<FoundSkill> memories(Location location, State state) {
        Map<String, String> projectsByFolder = new HashMap<>();
        json(locations.home().resolve(".claude.json")).ifPresent(config ->
                config.path("projects").propertyNames().forEach(path -> projectsByFolder.put(encodeProject(path), path)));
        List<FoundSkill> out = new ArrayList<>();
        for (Path project : children(location.root())) {
            Path memory = project.resolve("memory");
            if (!Files.isDirectory(memory, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            List<Path> notes = Fingerprints.files(memory).stream()
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList();
            if (notes.isEmpty()) {
                continue;
            }
            Path index = memory.resolve("MEMORY.md");
            Path file = Files.isRegularFile(index) ? index : notes.getFirst();
            state.seen.add(file);
            String folderName = project.getFileName().toString();
            String projectPath = projectsByFolder.getOrDefault(folderName, folderName);
            String projectName = Path.of(projectPath.replace('\\', '/')).getFileName() == null ? folderName
                    : Path.of(projectPath.replace('\\', '/')).getFileName().toString();
            long facts = notes.stream().filter(n -> !n.getFileName().toString().equals("MEMORY.md")).count();
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Progetto", projectPath);
            details.put("Ricordi", String.valueOf(facts));
            details.put("Cartella", memory.toString());
            String relative = Fingerprints.relative(location.root(), memory);
            out.add(new FoundSkill(FoundSkill.idOf(location.id(), relative), location.app(), location.id(), Scope.KNOWLEDGE,
                    FoundSkill.KNOWLEDGE, "Memoria · " + projectName,
                    facts + (facts == 1 ? " ricordo" : " ricordi") + " di Claude Code su questo progetto", INSTRUCTIONS,
                    null, null, null, null, null, file, memory, relative, false, Fingerprints.quick(memory, ""),
                    modified(file), null, Collections.unmodifiableMap(details)));
        }
        return out;
    }

    /**
     * The application's MCP servers: those its own configuration declares, and
     * those its plugins brought (collected while the plugins were read). One item
     * per name -- Slack offered by seven plugins is one connector, provided by seven.
     */
    private List<FoundSkill> connectors(Location location, State state) {
        String app = location.app();
        Path config = location.root();
        switch (location.id()) {
            case "claude-code.connectors" -> json(config).ifPresent(claudeJson -> {
                mcpServers(claudeJson.path("mcpServers"), "configurato da te").forEach(c -> state.connector(app, c));
                for (Map.Entry<String, JsonNode> project : claudeJson.path("projects").properties()) {
                    mcpServers(project.getValue().path("mcpServers"), "progetto " + project.getKey())
                            .forEach(c -> state.connector(app, c));
                }
                for (JsonNode name : claudeJson.path("claudeAiMcpEverConnected")) {
                    String label = name.asString("").replaceFirst("^claude\\.ai\\s+", "").strip();
                    if (!label.isEmpty()) {
                        state.connector(app, new RawConnector(label, "claude.ai", null, "account claude.ai", Scope.ACCOUNT, null));
                    }
                }
            });
            case "claude-desktop.connectors" -> {
                Path desktop = config.getParent();
                json(config).ifPresent(installs -> {
                    for (Map.Entry<String, JsonNode> extension : installs.path("extensions").properties()) {
                        JsonNode manifest = extension.getValue().path("manifest");
                        String name = Optional.ofNullable(text(manifest, "display_name")).or(() -> Optional.ofNullable(text(manifest, "name")))
                                .orElse(extension.getKey());
                        Boolean on = SkillMarketplaces.SLUG.matcher(extension.getKey()).matches()
                                ? json(desktop.resolve("Claude Extensions Settings").resolve(extension.getKey() + ".json"))
                                .map(s -> s.path("isEnabled").asBoolean(true)).orElse(null) : null;
                        String runtime = text(manifest.path("server"), "type");
                        state.connector(app, new RawConnector(name, "estensione desktop" + (runtime == null ? "" : " (" + runtime + ")"),
                                null, "estensione desktop", Scope.PERSONAL, on, text(manifest, "version")));
                    }
                });
                json(desktop.resolve("claude_desktop_config.json")).ifPresent(desktopConfig ->
                        mcpServers(desktopConfig.path("mcpServers"), "configurato da te").forEach(c -> state.connector(app, c)));
            }
            case "codex.connectors" -> {
                codexMcpServers(config).forEach(c -> state.connector(app, c));
                chatGptApps(config.getParent().resolve("cache").resolve("codex_apps_tools")).forEach(c -> state.connector(app, c));
            }
            case "antigravity.connectors", "gemini-cli.connectors" -> json(config).ifPresent(json -> {
                JsonNode servers = json.has("mcpServers") ? json.path("mcpServers") : json;
                mcpServers(servers, "configurato da te").forEach(c -> state.connector(app, c));
            });
            default -> {
            }
        }
        // One item per name.
        Map<String, List<RawConnector>> byName = new LinkedHashMap<>();
        for (RawConnector c : state.connectors.getOrDefault(app, List.of())) {
            byName.computeIfAbsent(family(c.name()), k -> new ArrayList<>()).add(c);
        }
        List<FoundSkill> out = new ArrayList<>();
        byName.forEach((family, list) -> {
            List<RawConnector> ordered = list.stream()
                    .sorted(Comparator.comparingInt((RawConnector c) -> rank(c.scope()))).toList();
            RawConnector first = ordered.getFirst();
            Set<String> transports = new LinkedHashSet<>();
            Set<String> targets = new LinkedHashSet<>();
            Set<String> sources = new LinkedHashSet<>();
            ordered.forEach(c -> {
                transports.add(c.transport());
                if (c.target() != null) {
                    targets.add(c.target());
                }
                sources.add(c.source());
            });
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Tipo", String.join(", ", transports));
            if (!targets.isEmpty()) {
                boolean remote = transports.stream().anyMatch(t -> t.startsWith("http") || t.equals("sse"));
                details.put(remote ? "Indirizzo" : "Avvio", abbreviate(targets, 3));
            }
            details.put("Fornito da", abbreviate(sources, 8));
            if (sources.stream().anyMatch(s -> s.startsWith("configurato") || s.startsWith("progetto") || s.startsWith("estensione"))) {
                details.put("Configurazione", config.toString());
            }
            ordered.stream().map(RawConnector::version).filter(v -> v != null).findFirst()
                    .ifPresent(v -> details.put("Versione", v));
            ordered.stream().map(RawConnector::tools).filter(v -> v != null).findFirst()
                    .ifPresent(v -> details.put("Strumenti", v));
            Boolean enabled = ordered.stream().map(RawConnector::enabled).filter(e -> e != null).findFirst().orElse(null);
            String description = ordered.stream().map(RawConnector::description).filter(d -> d != null && !d.isBlank())
                    .findFirst().orElse(first.transport() + (targets.isEmpty() ? "" : " · " + targets.iterator().next())
                            + (sources.size() > 1 ? " · da " + sources.size() + " fonti" : ""));
            String fingerprint = Fingerprints.sha256(String.join("\n", ordered.stream()
                    .map(c -> c.source() + "|" + c.transport() + "|" + c.target() + "|" + c.enabled()).sorted().toList()));
            String relative = "mcp/" + family;
            out.add(new FoundSkill(FoundSkill.idOf(location.id(), relative), app, location.id(), first.scope(),
                    FoundSkill.CONNECTOR, first.name(), description, connectorCategory(first.name()),
                    first.scope() == Scope.PLUGIN ? first.source().replaceFirst("^plugin ", "") : null, null, null, enabled, null,
                    config, config.getParent(), relative, false, fingerprint,
                    Files.exists(config) ? modified(config) : null, null, Collections.unmodifiableMap(details)));
        });
        return out;
    }

    // --- one item -----------------------------------------------------------------------

    private List<FoundSkill> skillsBelow(Location location, Path folder, Plugin plugin, State state) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path md : walkSkillMds(folder)) {
            skill(location, location.root(), md.getParent(), md, Scope.PLUGIN, plugin, state).ifPresent(out::add);
        }
        return out;
    }

    private Optional<FoundSkill> skill(Location location, Path root, Path folder, Path md, Scope scope, Plugin plugin,
                                       State state) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(md, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_SKILL_MD) {
                return Optional.empty();
            }
            state.seen.add(md);
            Cached cached = cache.get(md);
            if (cached == null || cached.size() != attributes.size() || cached.modified() != attributes.lastModifiedTime().toMillis()) {
                byte[] bytes = Files.readAllBytes(md);
                String text = new String(bytes, StandardCharsets.UTF_8);
                cached = new Cached(attributes.size(), attributes.lastModifiedTime().toMillis(), Fingerprints.sha256(bytes),
                        Map.copyOf(Frontmatter.read(text)));
                cache.put(md, cached);
            }
            Map<String, String> front = cached.frontmatter();
            String name = Optional.ofNullable(blankToNull(front.get("name"))).orElse(folder.getFileName().toString());
            String description = blankToNull(front.get("description"));
            String version = Optional.ofNullable(blankToNull(front.get("version"))).orElse(blankToNull(front.get("metadata.version")));
            String relative = Fingerprints.relative(root, md);
            String pluginName = plugin == null ? null : plugin.name();
            return Optional.of(new FoundSkill(FoundSkill.idOf(location.id(), relative), location.app(), location.id(), scope,
                    FoundSkill.SKILL, name, description, SkillCategories.of(name, pluginName, description), pluginName,
                    plugin == null ? null : plugin.version(), plugin == null ? null : plugin.marketplace(),
                    plugin == null ? null : plugin.enabled(), version, md, folder, relative,
                    location.writable() && scope == Scope.PERSONAL, Fingerprints.quick(folder, cached.sha()),
                    attributes.lastModifiedTime().toInstant(), HubMarker.read(folder).orElse(null), Map.of()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * A plugin as an item of its own -- even one without any skill, like a plugin
     * made only of hooks -- with what it is made of. The MCP servers it declares
     * are handed to its application's connectors.
     */
    private FoundSkill pluginItem(Location location, Path folder, Plugin plugin, State state) {
        Path manifestFile = PLUGIN_MANIFESTS.stream().map(folder::resolve).filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .findFirst().orElse(null);
        JsonNode manifest = manifestFile == null ? null : json(manifestFile).orElse(null);
        String description = manifest == null ? null : text(manifest, "description");
        String author = manifest == null ? null : Optional.ofNullable(text(manifest.path("author"), "name"))
                .orElse(text(manifest, "author"));
        int skills = walkSkillMds(folder).size();
        int commands = countMarkdown(folder.resolve("commands"));
        int agents = countMarkdown(folder.resolve("agents"));
        boolean hooks = Files.isRegularFile(folder.resolve("hooks").resolve("hooks.json"))
                || Files.isRegularFile(folder.resolve("hooks.json")) || (manifest != null && manifest.has("hooks"));
        List<String> servers = new ArrayList<>();
        for (String file : List.of(".mcp.json", "mcp.json")) {
            json(folder.resolve(file)).ifPresent(mcp -> {
                JsonNode list = mcp.has("mcpServers") ? mcp.path("mcpServers") : mcp;
                for (RawConnector c : mcpServers(list, "plugin " + plugin.name())) {
                    servers.add(c.name());
                    state.connector(location.app(), new RawConnector(c.name(), c.transport(), c.target(), c.source(),
                            Scope.PLUGIN, plugin.enabled(), null));
                }
            });
        }
        Map<String, String> details = new LinkedHashMap<>();
        if (plugin.version() != null) {
            details.put("Versione", plugin.version());
        }
        if (plugin.marketplace() != null) {
            details.put("Marketplace", plugin.marketplace());
        }
        if (author != null) {
            details.put("Autore", author);
        }
        details.put("Skill", String.valueOf(skills));
        details.put("Comandi", String.valueOf(commands));
        details.put("Agenti", String.valueOf(agents));
        details.put("Hook", hooks ? "sì" : "no");
        details.put("Connettori", servers.isEmpty() ? "0" : servers.size() + " (" + abbreviate(new LinkedHashSet<>(servers), 6) + ")");
        if (plugin.enabled() != null) {
            details.put("Stato", plugin.enabled() ? "attivo" : "disattivato");
        }
        details.put("Cartella", folder.toString());
        String relative = "plugin:" + Fingerprints.relative(location.root(), folder);
        if (manifestFile != null) {
            state.seen.add(manifestFile);
        }
        String sha = manifestFile == null ? "" : Fingerprints.sha256(readQuietly(manifestFile));
        return new FoundSkill(FoundSkill.idOf(location.id(), relative), location.app(), location.id(), Scope.PLUGIN,
                FoundSkill.PLUGIN, plugin.name(), description, SkillCategories.of(plugin.name(), null, description),
                plugin.name(), plugin.version(), plugin.marketplace(), plugin.enabled(), plugin.version(),
                manifestFile == null ? folder : manifestFile, folder, relative, false, Fingerprints.quick(folder, sha),
                modified(manifestFile == null ? folder : manifestFile), null, Collections.unmodifiableMap(details));
    }

    private Plugin pluginOf(Path folder, String fallbackName, String fallbackVersion, String marketplace, Boolean enabled) {
        for (String manifest : PLUGIN_MANIFESTS) {
            Optional<JsonNode> json = json(folder.resolve(manifest));
            if (json.isPresent() && (text(json.get(), "version") != null || text(json.get(), "name") != null)) {
                String version = Optional.ofNullable(text(json.get(), "version")).orElse(fallbackVersion);
                // A Codex app plugin is a folder like "app-6a32…": its manifest says what it is called.
                String display = Optional.ofNullable(text(json.get().path("interface"), "displayName"))
                        .or(() -> Optional.ofNullable(text(json.get(), "displayName"))).orElse(fallbackName);
                return new Plugin(display, version, marketplace, enabled);
            }
        }
        return new Plugin(fallbackName, fallbackVersion, marketplace, enabled);
    }

    // --- MCP configurations ---------------------------------------------------------------

    /**
     * The servers of an {@code mcpServers} object: name, transport and where it runs --
     * the executable's file name, or a URL's host and path. Arguments, environment,
     * headers and query strings are not read.
     */
    private static List<RawConnector> mcpServers(JsonNode servers, String source) {
        List<RawConnector> out = new ArrayList<>();
        if (servers == null || !servers.isObject()) {
            return out;
        }
        for (Map.Entry<String, JsonNode> server : servers.properties()) {
            JsonNode value = server.getValue();
            if (!value.isObject() || server.getKey().isBlank()) {
                continue;
            }
            String url = Optional.ofNullable(text(value, "url")).orElse(text(value, "serverUrl"));
            String command = text(value, "command");
            String type = text(value, "type");
            String transport = type != null ? type.toLowerCase(Locale.ROOT) : url != null ? "http" : command != null ? "stdio" : "mcp";
            Boolean disabled = value.has("disabled") ? value.path("disabled").asBoolean(false) : null;
            out.add(new RawConnector(server.getKey().strip(), transport, url != null ? safeUrl(url) : executable(command), source,
                    Scope.PERSONAL, disabled == null ? null : !disabled));
        }
        return out;
    }

    private static final Pattern TOML_SERVER = Pattern.compile("^\\[mcp_servers\\.(\"([^\"]+)\"|([A-Za-z0-9_-]+))]\\s*$");
    private static final Pattern TOML_STRING = Pattern.compile("^(command|url|enabled)\\s*=\\s*(?:'([^']*)'|\"([^\"]*)\"|(true|false))\\s*$");

    /** {@code [mcp_servers.<name>]} sections of Codex's {@code config.toml}: {@code command}, {@code url}, {@code enabled}. */
    private static List<RawConnector> codexMcpServers(Path config) {
        List<RawConnector> out = new ArrayList<>();
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try {
            String current = null;
            Map<String, String> values = new HashMap<>();
            List<String> lines = new ArrayList<>(Files.readAllLines(config, StandardCharsets.UTF_8));
            lines.add("[end]");
            for (String line : lines) {
                String stripped = line.strip();
                if (stripped.startsWith("[")) {
                    if (current != null) {
                        String url = values.get("url");
                        String command = values.get("command");
                        Boolean enabled = values.containsKey("enabled") ? Boolean.valueOf(values.get("enabled")) : null;
                        out.add(new RawConnector(current, url != null ? "http" : "stdio",
                                url != null ? safeUrl(url) : executable(command), "configurato da te", Scope.PERSONAL, enabled));
                    }
                    Matcher section = TOML_SERVER.matcher(stripped);
                    current = section.matches() ? Optional.ofNullable(section.group(2)).orElse(section.group(3)) : null;
                    values.clear();
                    continue;
                }
                Matcher value = TOML_STRING.matcher(stripped);
                if (current != null && value.matches()) {
                    values.put(value.group(1), Stream.of(value.group(2), value.group(3), value.group(4))
                            .filter(v -> v != null).findFirst().orElse(""));
                }
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        return out;
    }

    /**
     * The apps (connectors) of the user's ChatGPT account, as Codex caches their
     * tools ({@code ~/.codex/cache/codex_apps_tools/*.json}): one per connector, with
     * how many tools it has. Only names, descriptions and counts are read.
     */
    private static List<RawConnector> chatGptApps(Path folder) {
        List<RawConnector> out = new ArrayList<>();
        Map<String, Integer> tools = new LinkedHashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        Optional<Path> newest;
        try (Stream<Path> list = Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) ? Files.list(folder) : Stream.empty()) {
            newest = list.filter(f -> f.getFileName().toString().endsWith(".json")).max(Comparator.comparingLong(SkillScanner::modifiedMillis));
        } catch (IOException e) {
            return out;
        }
        newest.flatMap(SkillScanner::json).ifPresent(cache -> {
            for (JsonNode tool : cache.path("tools")) {
                String name = text(tool, "connector_name");
                if (name == null || name.isBlank() || name.length() > 120) {
                    continue;
                }
                tools.merge(name, 1, Integer::sum);
                String description = text(tool, "namespace_description");
                if (description != null && !descriptions.containsKey(name)) {
                    String line = description.lines().findFirst().orElse("").strip();
                    descriptions.put(name, line.length() > 200 ? line.substring(0, 200) + "…" : line);
                }
            }
        });
        tools.forEach((name, count) -> out.add(new RawConnector(name, "app ChatGPT", null, "account ChatGPT", Scope.ACCOUNT,
                null, null, count + (count == 1 ? " strumento" : " strumenti"), descriptions.get(name))));
        return out;
    }

    /** {@code https://host/path} -- no credentials, no query, no fragment. */
    static String safeUrl(String url) {
        try {
            URI uri = URI.create(url.strip());
            if (uri.getHost() == null) {
                return null;
            }
            return (uri.getScheme() == null ? "" : uri.getScheme() + "://") + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The file name of an executable, never its folder or its arguments. */
    static String executable(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.strip().replace('\\', '/');
        String last = normalized.substring(normalized.lastIndexOf('/') + 1);
        int space = last.indexOf(' ');
        return space > 0 ? last.substring(0, space) : last;
    }

    private static SkillCategories.Category connectorCategory(String name) {
        SkillCategories.Category category = SkillCategories.of(name, null, null);
        return category.equals(SkillCategories.OTHER) ? CONNECTORS : category;
    }

    private static int rank(Scope scope) {
        return switch (scope) {
            case PERSONAL -> 0;
            case ACCOUNT -> 1;
            default -> 2;
        };
    }

    // --- files --------------------------------------------------------------------------

    private static List<Path> children(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !SKIPPED.contains(p.getFileName().toString()))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Optional<Path> skillMd(Path folder) {
        for (String name : List.of("SKILL.md", "skill.md", "Skill.md")) {
            Path md = folder.resolve(name);
            if (Files.isRegularFile(md, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(md);
            }
        }
        return Optional.empty();
    }

    /** Every SKILL.md below a folder, without descending into links, hidden folders or dependency trees. */
    private static List<Path> walkSkillMds(Path folder) {
        List<Path> out = new ArrayList<>();
        walk(folder, 0, out);
        return out;
    }

    private static void walk(Path folder, int depth, List<Path> out) {
        if (depth > MAX_DEPTH || out.size() > MAX_PER_LOCATION) {
            return;
        }
        Optional<Path> md = skillMd(folder);
        if (md.isPresent() && depth > 0) {
            out.add(md.get());
            return;
        }
        for (Path child : children(folder)) {
            walk(child, depth + 1, out);
        }
    }

    private static int countMarkdown(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        return (int) Fingerprints.files(folder).stream()
                .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")).count();
    }

    private static Optional<Path> newest(List<Path> manifests) {
        return manifests.stream().max(Comparator.comparingLong(SkillScanner::freshness));
    }

    /** A manifest's own {@code lastUpdated}, or its file's modification time when it has none. */
    private static long freshness(Path manifest) {
        long stamp = json(manifest).map(j -> j.path("lastUpdated").asLong(0L)).orElse(0L);
        return stamp > 0 ? stamp : modifiedMillis(manifest);
    }

    static Optional<JsonNode> json(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > 8 * 1024 * 1024) {
                return Optional.empty();
            }
            return Optional.of(JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static final Pattern TOML_PLUGIN = Pattern.compile("^\\[plugins\\.\"([^\"]+)\"]\\s*$");
    private static final Pattern TOML_ENABLED = Pattern.compile("^enabled\\s*=\\s*(true|false)\\s*$");

    /** {@code [plugins."name@marketplace"]} / {@code enabled = true} of Codex's {@code config.toml}. */
    static Map<String, Boolean> codexEnabled(Path config) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try {
            String current = null;
            for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                Matcher section = TOML_PLUGIN.matcher(stripped);
                if (section.matches()) {
                    current = section.group(1);
                    continue;
                }
                if (stripped.startsWith("[")) {
                    current = null;
                    continue;
                }
                Matcher enabled = TOML_ENABLED.matcher(stripped);
                if (current != null && enabled.matches()) {
                    out.put(current, Boolean.parseBoolean(enabled.group(1)));
                }
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        return out;
    }

    /**
     * The folder name Claude Code gives a project: its path with every character
     * that is not a letter, a digit or a dash turned into a dash.
     */
    static String encodeProject(String path) {
        return path.replaceAll("[^A-Za-z0-9-]", "-");
    }

    private static String family(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
    }

    private static String abbreviate(Set<String> values, int max) {
        List<String> list = new ArrayList<>(values);
        return list.size() <= max ? String.join(", ", list)
                : String.join(", ", list.subList(0, max)) + " e altri " + (list.size() - max);
    }

    private static String firstLine(String text) {
        return text.lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("---") && !l.startsWith("<"))
                .findFirst().map(l -> l.length() > 300 ? l.substring(0, 300) + "…" : l).orElse(null);
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static long modifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Instant modified(Path path) {
        return Instant.ofEpochMilli(modifiedMillis(path));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.isContainer() ? null : value.asString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
