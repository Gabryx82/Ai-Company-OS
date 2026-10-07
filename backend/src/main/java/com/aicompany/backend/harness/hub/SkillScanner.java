package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.hub.SkillLocations.Layout;
import com.aicompany.backend.harness.hub.SkillLocations.Location;
import com.aicompany.backend.harness.hub.SkillLocations.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Reads every application's skills and knowledge from disk (ADR-036 §2), with
 * the layout each one uses: personal folders, plugin trees, Codex's plugin
 * cache, Claude Code's plugin store, Claude Desktop's per-account manifests,
 * global instruction files and knowledge items.
 *
 * <p>Read-only. Nothing below a location's root is ever followed through a link,
 * and a walk stops at {@value #MAX_DEPTH} levels and {@value #MAX_PER_LOCATION}
 * skills per location. A {@code SKILL.md} whose size and modification time did
 * not change since the previous scan is not read again.
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

    /** One scan: what was found, when, and what the scan could not read or chose to leave out. */
    public record Scan(Instant at, List<FoundSkill> skills, List<String> notes) {
    }

    private record Cached(long size, long modified, String sha, Map<String, String> frontmatter) {
    }

    private record Plugin(String name, String version, String marketplace, Boolean enabled) {
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

    /** Reads every location now. One scan at a time. */
    public synchronized Scan scan() {
        List<FoundSkill> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<Path> seen = ConcurrentHashMap.newKeySet();
        for (Location location : locations.locations()) {
            try {
                List<FoundSkill> found = switch (location.layout()) {
                    case SKILL_FOLDERS -> skillFolders(location, location.root(), null, seen);
                    case PLUGIN_SKILLS -> pluginSkills(location, seen);
                    case CODEX_PLUGINS -> codexPlugins(location, seen);
                    case CLAUDE_CODE_PLUGINS -> claudeCodePlugins(location, seen);
                    case CLAUDE_DESKTOP -> claudeDesktop(location, seen, notes);
                    case SINGLE_FILE -> singleFile(location);
                    case KNOWLEDGE_ITEMS -> knowledgeItems(location, seen);
                };
                if (found.size() > MAX_PER_LOCATION) {
                    notes.add(location.label() + ": mostrate le prime " + MAX_PER_LOCATION + " di " + found.size());
                    found = found.subList(0, MAX_PER_LOCATION);
                }
                out.addAll(found);
            } catch (RuntimeException e) {
                LOG.warn("Skill location {} could not be read", location.id(), e);
                notes.add(location.label() + " (" + location.app() + "): non leggibile -- " + e.getMessage());
            }
        }
        cache.keySet().retainAll(seen);
        Scan scan = new Scan(Instant.now(), List.copyOf(out), List.copyOf(notes));
        last = scan;
        return scan;
    }

    // --- layouts ----------------------------------------------------------------------

    /** {@code <root>/<name>/SKILL.md}: each folder with a SKILL.md is a skill. */
    private List<FoundSkill> skillFolders(Location location, Path root, Plugin plugin, Set<Path> seen) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path folder : children(root)) {
            skillMd(folder).flatMap(md -> skill(location, location.root(), folder, md, location.scope(), plugin, seen))
                    .ifPresent(out::add);
        }
        return out;
    }

    /** {@code <root>/<plugin>/...}: every SKILL.md below each plugin folder. */
    private List<FoundSkill> pluginSkills(Location location, Set<Path> seen) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path pluginFolder : children(location.root())) {
            Plugin plugin = pluginOf(pluginFolder, pluginFolder.getFileName().toString(), null, null);
            out.addAll(skillsBelow(location, pluginFolder, plugin, seen));
        }
        return out;
    }

    /** {@code <cache>/<marketplace>/<plugin>/<version>/}: the newest version folder of each plugin. */
    private List<FoundSkill> codexPlugins(Location location, Set<Path> seen) {
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
                Plugin plugin = pluginOf(newest.get(), name, newest.get().getFileName().toString(), market);
                plugin = new Plugin(plugin.name(), plugin.version(), market, enabled.get(name + "@" + market));
                out.addAll(skillsBelow(location, newest.get(), plugin, seen));
            }
        }
        return out;
    }

    /** {@code installed_plugins.json}: each plugin's install path, which must stay inside the plugin store. */
    private List<FoundSkill> claudeCodePlugins(Location location, Set<Path> seen) {
        List<FoundSkill> out = new ArrayList<>();
        Path index = location.root().resolve("installed_plugins.json");
        Optional<JsonNode> installed = json(index);
        if (installed.isEmpty()) {
            return out;
        }
        Map<String, Boolean> enabled = new HashMap<>();
        json(location.root().getParent().resolve("settings.json")).ifPresent(settings ->
                settings.path("enabledPlugins").properties().forEach(e -> enabled.put(e.getKey(), e.getValue().asBoolean(false))));
        JsonNode plugins = installed.get().has("plugins") ? installed.get().path("plugins") : installed.get();
        for (Map.Entry<String, JsonNode> entry : plugins.properties()) {
            JsonNode install = entry.getValue().isArray() ? entry.getValue().path(0) : entry.getValue();
            String path = text(install, "installPath");
            if (path == null) {
                continue;
            }
            Path folder = Path.of(path).toAbsolutePath().normalize();
            if (!folder.startsWith(location.root()) || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            String[] nameAt = entry.getKey().split("@", 2);
            Plugin plugin = new Plugin(nameAt[0], text(install, "version"), nameAt.length > 1 ? nameAt[1] : null,
                    enabled.containsKey(entry.getKey()) ? enabled.get(entry.getKey()) : null);
            out.addAll(skillsBelow(location, folder, plugin, seen));
        }
        return out;
    }

    /**
     * Claude Desktop keeps, per account, a manifest of its skills
     * ({@code skills-plugin/<org>/<account>/manifest.json}) and one of its plugins
     * ({@code <account>/<org>/rpm/manifest.json}). Only the account updated last is
     * the one in use; the others are counted, not listed.
     */
    private List<FoundSkill> claudeDesktop(Location location, Set<Path> seen, List<String> notes) {
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
            notes.add("Claude Desktop: " + (skillManifests.size() - 1) + " altro account trovato, non in uso: non elencato");
        }
        skills.flatMap(SkillScanner::json).ifPresent(manifest -> {
            Path skillsRoot = skills.get().getParent().resolve("skills");
            for (JsonNode entry : manifest.path("skills")) {
                String id = text(entry, "skillId");
                if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
                    continue;
                }
                Path folder = skillsRoot.resolve(id);
                Scope scope = "anthropic".equalsIgnoreCase(text(entry, "creatorType")) ? Scope.SYSTEM : Scope.PERSONAL;
                Boolean on = entry.has("enabled") ? entry.path("enabled").asBoolean(true) : null;
                Plugin owner = new Plugin(null, null, scope == Scope.SYSTEM ? "anthropic" : null, on);
                skillMd(folder).flatMap(md -> skill(location, root, folder, md, scope, owner, seen)).ifPresent(out::add);
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
                String name = Optional.ofNullable(text(entry, "name")).orElse(id);
                Plugin plugin = pluginOf(folder, name, null, text(entry, "marketplaceName"));
                plugin = new Plugin(name, plugin.version(), plugin.marketplace(), true);
                out.addAll(skillsBelow(location, folder, plugin, seen));
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
            String description = text.lines().map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("---") && !l.startsWith("<"))
                    .findFirst().map(l -> l.length() > 300 ? l.substring(0, 300) + "…" : l).orElse(null);
            String name = file.getFileName().toString();
            return List.of(new FoundSkill(FoundSkill.idOf(location.id(), name), location.app(), location.id(),
                    location.scope(), "KNOWLEDGE", name, description, INSTRUCTIONS, null, null, null, null, null,
                    file, file.getParent(), name, false, Fingerprints.sha256(text), modified(file), null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<FoundSkill> knowledgeItems(Location location, Set<Path> seen) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path folder : children(location.root())) {
            Optional<JsonNode> metadata = json(folder.resolve("metadata.json"));
            List<Path> markdown = Fingerprints.files(folder).stream()
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")).toList();
            Path file = markdown.isEmpty() ? folder.resolve("metadata.json") : markdown.getFirst();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            seen.add(file);
            String name = metadata.map(m -> text(m, "title")).orElse(folder.getFileName().toString());
            String description = metadata.map(m -> text(m, "summary")).orElse(null);
            String relative = Fingerprints.relative(location.root(), folder);
            out.add(new FoundSkill(FoundSkill.idOf(location.id(), relative), location.app(), location.id(),
                    Scope.KNOWLEDGE, "KNOWLEDGE", name, description, INSTRUCTIONS, null, null, null, null, null,
                    file, folder, relative, false, Fingerprints.quick(folder, ""), modified(file), null));
        }
        return out;
    }

    static final SkillCategories.Category INSTRUCTIONS = new SkillCategories.Category("istruzioni", "Istruzioni e knowledge");

    // --- one skill ----------------------------------------------------------------------

    private List<FoundSkill> skillsBelow(Location location, Path folder, Plugin plugin, Set<Path> seen) {
        List<FoundSkill> out = new ArrayList<>();
        for (Path md : walkSkillMds(folder)) {
            skill(location, location.root(), md.getParent(), md, Scope.PLUGIN, plugin, seen).ifPresent(out::add);
        }
        return out;
    }

    private Optional<FoundSkill> skill(Location location, Path root, Path folder, Path md, Scope scope, Plugin plugin,
                                       Set<Path> seen) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(md, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_SKILL_MD) {
                return Optional.empty();
            }
            seen.add(md);
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
                    "SKILL", name, description, SkillCategories.of(name, pluginName, description), pluginName,
                    plugin == null ? null : plugin.version(), plugin == null ? null : plugin.marketplace(),
                    plugin == null ? null : plugin.enabled(), version, md, folder, relative,
                    location.writable() && scope == Scope.PERSONAL, Fingerprints.quick(folder, cached.sha()),
                    attributes.lastModifiedTime().toInstant(), HubMarker.read(folder).orElse(null)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Plugin pluginOf(Path folder, String fallbackName, String fallbackVersion, String marketplace) {
        for (String manifest : List.of(".claude-plugin/plugin.json", ".codex-plugin/plugin.json", "plugin.json",
                "gemini-extension.json", "package.json")) {
            Optional<JsonNode> json = json(folder.resolve(manifest));
            if (json.isPresent() && (text(json.get(), "version") != null || text(json.get(), "name") != null)) {
                String version = Optional.ofNullable(text(json.get(), "version")).orElse(fallbackVersion);
                // A Codex app plugin is a folder like "app-6a32…": its manifest says what it is called.
                String display = Optional.ofNullable(text(json.get().path("interface"), "displayName"))
                        .or(() -> Optional.ofNullable(text(json.get(), "displayName"))).orElse(fallbackName);
                return new Plugin(display, version, marketplace, null);
            }
        }
        return new Plugin(fallbackName, fallbackVersion, marketplace, null);
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
            if (Files.size(file) > 4 * 1024 * 1024) {
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
