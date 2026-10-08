package com.aicompany.backend.harness.hub;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The online skill catalogs (ADR-036 §6), organised like the Mockup Hub: a
 * shelf of providers, some searchable and installable from here, the others
 * opened in the browser.
 *
 * <ul>
 *   <li><b>skills.sh</b> -- search API; a skill is a folder of a GitHub repository,
 *       resolved through the repository's tree;</li>
 *   <li><b>ClawHub</b> -- search, versions with a file list and a security status, files;</li>
 *   <li><b>GitHub repositories</b> (Anthropic, OpenAI, Vercel) -- browsed through the
 *       tree API, files read from {@code raw.githubusercontent.com}.</li>
 * </ul>
 *
 * Every URI is built here from parts validated by {@link #OWNER}, {@link #SLUG} and
 * {@link PackagePaths}; nothing from a request is used as a URL.
 */
@Service
public class SkillMarketplaces {

    private static final Logger LOG = LoggerFactory.getLogger(SkillMarketplaces.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final JsonMapper LENIENT = JsonMapper.builder().build();

    static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$");
    static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$");
    private static final Duration TREE_TTL = Duration.ofMinutes(10);
    private static final int MAX_TREE = 12 * 1024 * 1024;
    private static final int MAX_JSON = 2 * 1024 * 1024;

    public enum Api { SKILLS_SH, CLAWHUB, GITHUB, CLAUDE_MARKETPLACE, CODEX_MARKETPLACE, MCP_REGISTRY }

    /**
     * A catalog. {@code kind} is what it offers -- skills (the default), plugins or
     * connectors; {@code marketplace} the name an application's CLI knows a plugin
     * marketplace by; {@code targets} the applications its items install into.
     */
    public record Provider(String key, String name, String description, String homepage, @Nullable String searchUrl,
                           @Nullable Api api, @Nullable String repo, boolean official, boolean community, String kind, @Nullable String marketplace,
                           @Nullable List<String> targets) {
        public String kindOrSkill() {
            return kind == null ? "SKILL" : kind;
        }
    }

    /** One item a catalog offers. {@code ref} is what preview and install take back. */
    public record Result(String provider, String ref, String name, @Nullable String description, @Nullable String author, @Nullable String source,
                         @Nullable Long installs, @Nullable String version, @Nullable String url, boolean suspicious, String kind,
                         @Nullable List<String> targets, Map<String, String> details) {
        /** A skill: the first kind the Marketplace knew. */
        public Result(String provider, String ref, String name, String description, String author, String source,
                      Long installs, String version, String url, boolean suspicious) {
            this(provider, ref, name, description, author, source, installs, version, url, suspicious, "SKILL", null, Map.of());
        }
    }

    public record SearchResults(List<Result> results, List<ProviderError> errors) {
    }

    public record ProviderError(String provider, String message) {
    }

    public record PackageFile(String path, long size) {
    }

    /** What installing would bring, before anything is written. */
    public record Preview(String provider, String ref, String name, String description, @Nullable String version,
                          String sourceUrl, List<PackageFile> files, String skillMd, @Nullable String security,
                          List<String> warnings) {
    }

    /**
     * A package, downloaded. {@code reference} is canonical -- what update checks
     * take back -- and {@code fingerprint} the upstream state it was taken at.
     */
    public record Package(String provider, String reference, String name, String description, String version,
                          String sourceUrl, Map<String, byte[]> files, String fingerprint) {
    }

    /** The upstream state of an installed package, for an update check. */
    public record Latest(String version, String fingerprint) {
    }

    private record TreeEntry(String path, String type, String mode, String sha, long size) {
    }

    private record Tree(Instant fetchedAt, List<TreeEntry> entries) {
    }

    private record Folder(String owner, String repo, String path) {
        String reference() {
            return owner + "/" + repo + ":" + path;
        }
    }

    private final HubWeb web;
    private final ExtensionMarketplaces extensions;
    private final List<Provider> providers;
    private final Map<String, Tree> trees = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> frontmatters = new ConcurrentHashMap<>();
    /** One task per provider of a search; they wait on {@link #pool}, never on themselves. */
    private final ExecutorService searches = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "skill-hub-search");
        t.setDaemon(true);
        return t;
    });
    /** Single downloads: a file, a frontmatter. Nothing submitted here waits on this pool. */
    private final ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "skill-hub-web");
        t.setDaemon(true);
        return t;
    });

    public SkillMarketplaces(HubWeb web, ExtensionMarketplaces extensions) {
        this.web = web;
        this.extensions = extensions;
        try (InputStream in = new ClassPathResource("catalog/skill-marketplaces.json").getInputStream()) {
            this.providers = List.of(JSON.readValue(in, Provider[].class));
        } catch (IOException e) {
            throw new UncheckedIOException("catalog/skill-marketplaces.json is unreadable", e);
        }
    }

    public List<Provider> providers() {
        return providers;
    }

    public Provider provider(String key) {
        return providers.stream().filter(p -> p.key().equals(key)).findFirst()
                .orElseThrow(() -> HubProblemException.notFound("No marketplace '" + key + "'"));
    }

    /** A catalog of plugins or connectors is installed through {@link HubInstaller}, never as a skill package. */
    private static Provider skills(Provider provider) {
        if (!"SKILL".equals(provider.kindOrSkill())) {
            throw HubProblemException.packageRefused(provider.name() + " offre " + ("PLUGIN".equals(provider.kindOrSkill())
                    ? "plugin" : "connettori") + ", non skill: si installano dalla loro scheda");
        }
        return provider;
    }

    // --- search and browse ------------------------------------------------------------

    /** Searches every searchable provider (or the ones named), each on its own: one failing does not hide the others. */
    public SearchResults search(String query, List<String> only) {
        return search(query, only, null);
    }

    /** As {@link #search(String, List)}, among the catalogs of one kind (skills, plugins, connectors) when named. */
    public SearchResults search(String query, List<String> only, String kind) {
        String q = query == null ? "" : query.strip();
        if (q.length() < 2) {
            throw HubProblemException.packageRefused("Scrivi almeno due caratteri da cercare");
        }
        List<Provider> chosen = providers.stream().filter(p -> p.api() != null)
                .filter(p -> kind == null || kind.isBlank() || kind.equals(p.kindOrSkill()))
                .filter(p -> only == null || only.isEmpty() || only.contains(p.key())).toList();
        Map<Provider, Future<List<Result>>> running = new LinkedHashMap<>();
        for (Provider provider : chosen) {
            running.put(provider, searches.submit(() -> searchOne(provider, q)));
        }
        List<Result> results = new ArrayList<>();
        List<ProviderError> errors = new ArrayList<>();
        running.forEach((provider, future) -> {
            try {
                results.addAll(future.get(provider.api() == Api.MCP_REGISTRY ? 50 : 25, TimeUnit.SECONDS));
            } catch (java.util.concurrent.ExecutionException e) {
                errors.add(new ProviderError(provider.key(), message(e.getCause())));
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                errors.add(new ProviderError(provider.key(), provider.name() + " non ha risposto in tempo"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(new ProviderError(provider.key(), "Ricerca interrotta"));
            }
        });
        return new SearchResults(results, errors);
    }

    /** What a provider offers without a query: a repository's skills, ClawHub's trending ones. */
    public List<Result> browse(String providerKey) {
        Provider provider = provider(providerKey);
        if (provider.api() == null) {
            throw HubProblemException.notFound(provider.name() + " si apre nel browser: non ha un catalogo da sfogliare qui");
        }
        return switch (provider.api()) {
            case GITHUB -> githubBrowse(provider, null);
            case CLAWHUB -> clawhubList(provider);
            case SKILLS_SH -> searchOne(provider, "skill");
            case CLAUDE_MARKETPLACE, CODEX_MARKETPLACE, MCP_REGISTRY -> extensions.search(provider, null);
        };
    }

    private List<Result> searchOne(Provider provider, String q) {
        return switch (provider.api()) {
            case SKILLS_SH -> skillsShSearch(provider, q);
            case CLAWHUB -> clawhubSearch(provider, q);
            case GITHUB -> githubBrowse(provider, q);
            case CLAUDE_MARKETPLACE, CODEX_MARKETPLACE, MCP_REGISTRY -> extensions.search(provider, q);
        };
    }

    private List<Result> skillsShSearch(Provider provider, String q) {
        JsonNode body = json(uri("https://skills.sh/api/search?q=" + encode(q) + "&limit=30"));
        List<Result> out = new ArrayList<>();
        for (JsonNode skill : body.path("skills")) {
            String id = text(skill, "id");
            String source = text(skill, "source");
            if (id == null || source == null || id.split("/").length != 3) {
                continue;
            }
            String[] parts = id.split("/");
            if (!OWNER.matcher(parts[0]).matches() || !OWNER.matcher(parts[1]).matches() || !SLUG.matcher(parts[2]).matches()) {
                continue;
            }
            out.add(new Result(provider.key(), id, Optional.ofNullable(text(skill, "name")).orElse(parts[2]), null,
                    parts[0], source, skill.path("installs").asLong(0L), null,
                    "https://skills.sh/" + id, false));
        }
        return out;
    }

    private List<Result> clawhubSearch(Provider provider, String q) {
        JsonNode body = json(uri("https://clawhub.ai/api/v1/search?q=" + encode(q) + "&limit=30"));
        List<Result> out = new ArrayList<>();
        for (JsonNode item : body.path("results")) {
            String owner = text(item, "ownerHandle");
            String slug = text(item, "slug");
            if (owner == null || slug == null || !OWNER.matcher(owner).matches() || !SLUG.matcher(slug).matches()) {
                continue;
            }
            JsonNode skill = item.path("native").path("skill");
            out.add(new Result(provider.key(), owner + "/" + slug,
                    Optional.ofNullable(text(item, "displayName")).orElse(slug), text(item, "summary"), owner,
                    "clawhub.ai/" + owner, item.path("downloads").asLong(skill.path("stats").path("downloads").asLong(0L)),
                    null, "https://clawhub.ai/" + owner + "/skills/" + slug, skill.path("isSuspicious").asBoolean(false)));
        }
        return out;
    }

    private List<Result> clawhubList(Provider provider) {
        JsonNode body = json(uri("https://clawhub.ai/api/v1/skills?sort=trending&limit=40&nonSuspiciousOnly=true"));
        List<Result> out = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            String owner = text(item, "ownerHandle");
            String slug = text(item, "slug");
            if (owner == null || slug == null || !OWNER.matcher(owner).matches() || !SLUG.matcher(slug).matches()) {
                continue;
            }
            out.add(new Result(provider.key(), owner + "/" + slug,
                    Optional.ofNullable(text(item, "displayName")).orElse(slug), text(item, "summary"), owner,
                    "clawhub.ai/" + owner, item.path("stats").path("downloads").asLong(0L),
                    text(item.path("latestVersion"), "version"), "https://clawhub.ai/" + owner + "/skills/" + slug, false));
        }
        return out;
    }

    /**
     * The skills of a provider's repository, filtered by the query when there is
     * one -- on the folder, the name and the description. The frontmatters are
     * read in parallel and kept by blob sha: a second search reads nothing again.
     */
    private List<Result> githubBrowse(Provider provider, String q) {
        String[] repo = provider.repo().split("/");
        List<TreeEntry> entries = tree(repo[0], repo[1]);
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        Map<String, Future<Map<String, String>>> fronts = new LinkedHashMap<>();
        for (String folder : skillFolders(entries).stream().limit(150).toList()) {
            entry(entries, join(folder, "SKILL.md"))
                    .ifPresent(md -> fronts.put(folder, pool.submit(() -> frontmatter(repo[0], repo[1], md))));
        }
        List<Result> out = new ArrayList<>();
        fronts.forEach((folder, future) -> {
            Map<String, String> front = Map.of();
            try {
                front = future.get(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                LOG.debug("No frontmatter for {}", folder, e);
            }
            String name = Optional.ofNullable(front.get("name")).orElse(last(folder));
            String description = front.get("description");
            String text = (folder + " " + name + " " + Optional.ofNullable(description).orElse("")).toLowerCase(Locale.ROOT);
            if (needle != null && !text.contains(needle)) {
                return;
            }
            out.add(new Result(provider.key(), repo[0] + "/" + repo[1] + ":" + folder, name, description, repo[0],
                    provider.repo(), null, Optional.ofNullable(front.get("version")).orElse(front.get("metadata.version")),
                    "https://github.com/" + provider.repo() + "/tree/HEAD/" + folder, false));
        });
        return out;
    }

    // --- preview, download, latest ------------------------------------------------------

    public Preview preview(String providerKey, String ref) {
        Provider provider = skills(provider(providerKey));
        if (provider.api() == Api.CLAWHUB) {
            String[] parts = clawhubRef(ref);
            JsonNode detail = json(uri("https://clawhub.ai/api/v1/skills/" + parts[1] + "?ownerHandle=" + encode(parts[0])));
            String version = text(detail.path("latestVersion"), "version");
            JsonNode files = version == null ? null : json(uri("https://clawhub.ai/api/v1/skills/" + parts[1]
                    + "/versions/" + encode(version) + "?ownerHandle=" + encode(parts[0]))).path("version");
            List<PackageFile> list = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            if (files != null) {
                for (JsonNode f : files.path("files")) {
                    list.add(new PackageFile(text(f, "path"), f.path("size").asLong(0L)));
                }
            }
            String security = files == null ? null : text(files.path("security"), "status");
            if (files != null && files.path("security").path("hasWarnings").asBoolean(false)) {
                warnings.add("ClawHub segnala avvisi di sicurezza per questa versione: leggi i file prima di usarla.");
            }
            if (detail.path("moderation").path("isSuspicious").asBoolean(false)) {
                warnings.add("ClawHub la segna come sospetta.");
            }
            HubWeb.Response md = web.get(uri("https://clawhub.ai/api/v1/skills/" + parts[1] + "/file?path=SKILL.md&ownerHandle="
                    + encode(parts[0]) + (version == null ? "" : "&version=" + encode(version))), 512 * 1024);
            String skillMd = md.ok() ? md.text() : null;
            return new Preview(provider.key(), ref, Optional.ofNullable(text(detail.path("skill"), "displayName")).orElse(parts[1]),
                    text(detail.path("skill"), "summary"), version, "https://clawhub.ai/" + parts[0] + "/skills/" + parts[1],
                    list, skillMd, security, warnings);
        }
        Folder folder = resolveFolder(provider, ref);
        List<TreeEntry> entries = packageEntries(folder);
        TreeEntry md = entry(entries, "SKILL.md")
                .orElseThrow(() -> HubProblemException.packageRefused("The folder has no SKILL.md"));
        String skillMd = raw(folder.owner(), folder.repo(), join(folder.path(), "SKILL.md"), 512 * 1024).text();
        Map<String, String> front = Frontmatter.read(skillMd);
        List<String> warnings = new ArrayList<>();
        if (entries.stream().anyMatch(e -> e.path().matches("(?i).*\\.(ps1|sh|bat|cmd|exe|py|js|mjs)$"))) {
            warnings.add("Il pacchetto contiene script: un agente può eseguirli. Installa solo da fonti di cui ti fidi.");
        }
        return new Preview(provider.key(), ref, Optional.ofNullable(front.get("name")).orElse(last(folder.path())),
                front.get("description"), Optional.ofNullable(front.get("version")).orElse(front.get("metadata.version")),
                githubUrl(folder), entries.stream().map(e -> new PackageFile(e.path(), e.size())).toList(), skillMd,
                null, warnings);
    }

    /** Downloads a package whole, within the {@link PackagePaths} limits. */
    public Package fetch(String providerKey, String ref) {
        Provider provider = skills(provider(providerKey));
        if (provider.api() == Api.CLAWHUB) {
            String[] parts = clawhubRef(ref);
            JsonNode detail = json(uri("https://clawhub.ai/api/v1/skills/" + parts[1] + "?ownerHandle=" + encode(parts[0])));
            String version = text(detail.path("latestVersion"), "version");
            if (version == null) {
                throw HubProblemException.packageRefused("ClawHub has no published version of " + ref);
            }
            JsonNode listing = json(uri("https://clawhub.ai/api/v1/skills/" + parts[1] + "/versions/" + encode(version)
                    + "?ownerHandle=" + encode(parts[0]))).path("version");
            Map<String, byte[]> files = new TreeMap<>();
            long total = 0;
            for (JsonNode f : listing.path("files")) {
                String path = text(f, "path");
                if (path == null || PackagePaths.hidden(path)) {
                    continue;
                }
                PackagePaths.require(path);
                total += f.path("size").asLong(0L);
                PackagePaths.requireBudget(files.size() + 1, total);
                HubWeb.Response file = web.get(uri("https://clawhub.ai/api/v1/skills/" + parts[1] + "/file?path="
                        + encode(path) + "&version=" + encode(version) + "&ownerHandle=" + encode(parts[0])), PackagePaths.MAX_FILE);
                if (!file.ok()) {
                    throw HubProblemException.marketplace("ClawHub answered HTTP " + file.status() + " for " + path);
                }
                files.put(path, file.body());
            }
            if (!files.containsKey("SKILL.md")) {
                throw HubProblemException.packageRefused("The package has no SKILL.md");
            }
            return new Package(provider.key(), parts[0] + "/" + parts[1],
                    Optional.ofNullable(text(detail.path("skill"), "displayName")).orElse(parts[1]),
                    text(detail.path("skill"), "summary"), version, "https://clawhub.ai/" + parts[0] + "/skills/" + parts[1],
                    files, Fingerprints.sha256("clawhub:" + version));
        }
        Folder folder = resolveFolder(provider, ref);
        List<TreeEntry> entries = packageEntries(folder);
        if (entry(entries, "SKILL.md").isEmpty()) {
            throw HubProblemException.packageRefused("The folder has no SKILL.md");
        }
        Map<String, Future<HubWeb.Response>> downloads = new LinkedHashMap<>();
        for (TreeEntry e : entries) {
            downloads.put(e.path(), pool.submit(() -> raw(folder.owner(), folder.repo(), join(folder.path(), e.path()),
                    PackagePaths.MAX_FILE)));
        }
        Map<String, byte[]> files = new TreeMap<>();
        downloads.forEach((path, future) -> files.put(path, await(future).body()));
        Map<String, String> front = Frontmatter.read(new String(files.get("SKILL.md"), StandardCharsets.UTF_8));
        return new Package(provider.key(), folder.reference(), Optional.ofNullable(front.get("name")).orElse(last(folder.path())),
                front.get("description"), Optional.ofNullable(front.get("version")).orElse(front.get("metadata.version")),
                githubUrl(folder), files, githubFingerprint(entries));
    }

    /** The upstream state of an installed package, read fresh. */
    public Latest latest(String providerKey, String reference) {
        Provider provider = provider(providerKey);
        if (provider.api() == Api.CLAWHUB) {
            String[] parts = clawhubRef(reference);
            JsonNode detail = json(uri("https://clawhub.ai/api/v1/skills/" + parts[1] + "?ownerHandle=" + encode(parts[0])));
            String version = text(detail.path("latestVersion"), "version");
            return new Latest(version, version == null ? null : Fingerprints.sha256("clawhub:" + version));
        }
        Folder folder = canonical(reference);
        trees.remove(folder.owner() + "/" + folder.repo());
        List<TreeEntry> entries = packageEntries(folder);
        TreeEntry md = entry(entries, "SKILL.md").orElse(null);
        Map<String, String> front = md == null ? Map.of() : frontmatter(folder.owner(), folder.repo(),
                new TreeEntry(join(folder.path(), "SKILL.md"), md.type(), md.mode(), md.sha(), md.size()));
        return new Latest(Optional.ofNullable(front.get("version")).orElse(front.get("metadata.version")),
                entries.isEmpty() ? null : githubFingerprint(entries));
    }

    // --- GitHub ----------------------------------------------------------------------------

    /** skills.sh ids ({@code owner/repo/skill}) are resolved to the folder; canonical references pass through. */
    private Folder resolveFolder(Provider provider, String ref) {
        if (ref != null && ref.contains(":")) {
            Folder folder = canonical(ref);
            if (provider.api() == Api.GITHUB && !provider.repo().equals(folder.owner() + "/" + folder.repo())) {
                throw HubProblemException.notFound("The reference is not in " + provider.repo());
            }
            return folder;
        }
        if (provider.api() != Api.SKILLS_SH || ref == null || ref.split("/").length != 3) {
            throw HubProblemException.notFound("Not a skill reference: " + ref);
        }
        String[] parts = ref.split("/");
        if (!OWNER.matcher(parts[0]).matches() || !OWNER.matcher(parts[1]).matches() || !SLUG.matcher(parts[2]).matches()) {
            throw HubProblemException.notFound("Not a skill reference: " + ref);
        }
        List<TreeEntry> entries = tree(parts[0], parts[1]);
        List<String> folders = skillFolders(entries);
        String wanted = parts[2].toLowerCase(Locale.ROOT);
        Optional<String> byFolder = folders.stream().filter(f -> last(f).toLowerCase(Locale.ROOT).equals(wanted))
                .min(Comparator.comparing((String f) -> !f.contains("skills/")).thenComparing(String::length));
        if (byFolder.isPresent()) {
            return new Folder(parts[0], parts[1], byFolder.get());
        }
        // The folder is named otherwise: the skill's own name decides.
        for (String folder : folders.stream().limit(80).toList()) {
            TreeEntry md = entry(entries, join(folder, "SKILL.md")).orElse(null);
            if (md != null && wanted.equalsIgnoreCase(frontmatter(parts[0], parts[1], md).get("name"))) {
                return new Folder(parts[0], parts[1], folder);
            }
        }
        throw HubProblemException.notFound("The skill '" + parts[2] + "' was not found in github.com/" + parts[0] + "/" + parts[1]);
    }

    private static Folder canonical(String reference) {
        int colon = reference.indexOf(':');
        String[] repo = reference.substring(0, colon).split("/");
        String path = reference.substring(colon + 1);
        if (repo.length != 2 || !OWNER.matcher(repo[0]).matches() || !OWNER.matcher(repo[1]).matches()
                || (!path.isEmpty() && !PackagePaths.valid(path))) {
            throw HubProblemException.notFound("Not a skill reference: " + reference);
        }
        return new Folder(repo[0], repo[1], path);
    }

    /** The files of a skill folder, relative to it, within the limits; hidden files and links left out. */
    private List<TreeEntry> packageEntries(Folder folder) {
        String prefix = folder.path().isEmpty() ? "" : folder.path() + "/";
        List<TreeEntry> out = new ArrayList<>();
        long total = 0;
        for (TreeEntry e : tree(folder.owner(), folder.repo())) {
            if (!"blob".equals(e.type()) || !e.path().startsWith(prefix) || "120000".equals(e.mode())) {
                continue;
            }
            String relative = e.path().substring(prefix.length());
            if (PackagePaths.hidden(relative)) {
                continue;
            }
            PackagePaths.require(relative);
            total += e.size();
            PackagePaths.requireBudget(out.size() + 1, total);
            out.add(new TreeEntry(relative, e.type(), e.mode(), e.sha(), e.size()));
        }
        return out;
    }

    private List<TreeEntry> tree(String owner, String repo) {
        String key = owner + "/" + repo;
        Tree cached = trees.get(key);
        if (cached != null && cached.fetchedAt().plus(TREE_TTL).isAfter(Instant.now())) {
            return cached.entries();
        }
        HubWeb.Response response = web.get(uri("https://api.github.com/repos/" + owner + "/" + repo
                + "/git/trees/HEAD?recursive=1"), MAX_TREE);
        if (response.status() == 403 || response.status() == 429) {
            String reset = response.headers().get("x-ratelimit-reset");
            String when = reset == null ? "" : " (di nuovo disponibile alle "
                    + java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())
                    .format(Instant.ofEpochSecond(Long.parseLong(reset.strip()))) + ")";
            throw HubProblemException.marketplace("GitHub ha raggiunto il limite di richieste anonime" + when);
        }
        if (response.status() == 404) {
            throw HubProblemException.notFound("github.com/" + key + " does not exist or is private");
        }
        if (!response.ok()) {
            throw HubProblemException.marketplace("GitHub answered HTTP " + response.status());
        }
        List<TreeEntry> entries = new ArrayList<>();
        JsonNode body = parse(response);
        for (JsonNode e : body.path("tree")) {
            String path = text(e, "path");
            if (path != null) {
                entries.add(new TreeEntry(path, text(e, "type"), text(e, "mode"), text(e, "sha"), e.path("size").asLong(0L)));
            }
        }
        trees.put(key, new Tree(Instant.now(), List.copyOf(entries)));
        return entries;
    }

    private static List<String> skillFolders(List<TreeEntry> entries) {
        return entries.stream().filter(e -> "blob".equals(e.type()))
                .map(TreeEntry::path)
                .filter(p -> p.equals("SKILL.md") || p.endsWith("/SKILL.md"))
                .map(p -> p.equals("SKILL.md") ? "" : p.substring(0, p.length() - "/SKILL.md".length()))
                .filter(p -> p.isEmpty() || PackagePaths.valid(p))
                .sorted().toList();
    }

    private Map<String, String> frontmatter(String owner, String repo, TreeEntry md) {
        return frontmatters.computeIfAbsent(md.sha() == null ? owner + repo + md.path() : md.sha(), sha -> {
            try {
                HubWeb.Response response = raw(owner, repo, md.path(), 512 * 1024);
                return Map.copyOf(Frontmatter.read(response.text()));
            } catch (RuntimeException e) {
                LOG.debug("No frontmatter for {}/{}/{}", owner, repo, md.path(), e);
                return Map.of();
            }
        });
    }

    private HubWeb.Response raw(String owner, String repo, String path, int max) {
        try {
            URI uri = new URI("https", "raw.githubusercontent.com", "/" + owner + "/" + repo + "/HEAD/" + path, null);
            HubWeb.Response response = web.get(uri, max);
            if (!response.ok()) {
                throw HubProblemException.marketplace("GitHub answered HTTP " + response.status() + " for " + path);
            }
            return response;
        } catch (URISyntaxException e) {
            throw HubProblemException.packageRefused("Not a valid path: " + path);
        }
    }

    private static String githubFingerprint(List<TreeEntry> entries) {
        StringBuilder all = new StringBuilder();
        entries.stream().sorted(Comparator.comparing(TreeEntry::path))
                .forEach(e -> all.append(e.path()).append('\u0000').append(e.sha()).append('\n'));
        return Fingerprints.sha256("github:" + all);
    }

    private static String githubUrl(Folder folder) {
        return "https://github.com/" + folder.owner() + "/" + folder.repo() + "/tree/HEAD/" + folder.path();
    }

    // --- helpers -------------------------------------------------------------------------

    private static String[] clawhubRef(String ref) {
        String[] parts = ref == null ? new String[0] : ref.split("/");
        if (parts.length != 2 || !OWNER.matcher(parts[0]).matches() || !SLUG.matcher(parts[1]).matches()) {
            throw HubProblemException.notFound("Not a ClawHub reference: " + ref);
        }
        return parts;
    }

    private JsonNode json(URI uri) {
        HubWeb.Response response = web.get(uri, MAX_JSON);
        if (response.status() == 404) {
            throw HubProblemException.notFound(uri.getHost() + " does not know " + uri.getPath());
        }
        if (response.status() == 429) {
            throw HubProblemException.marketplace(uri.getHost() + " ha limitato le richieste: riprova fra poco");
        }
        if (!response.ok()) {
            throw HubProblemException.marketplace(uri.getHost() + " answered HTTP " + response.status());
        }
        return parse(response);
    }

    private static JsonNode parse(HubWeb.Response response) {
        try {
            return LENIENT.readTree(response.text());
        } catch (RuntimeException e) {
            throw HubProblemException.marketplace("The answer is not JSON");
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw HubProblemException.marketplace(message(e.getCause()));
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw HubProblemException.marketplace("Il download non è finito in tempo");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HubProblemException.marketplace("Download interrotto");
        }
    }

    private static Optional<TreeEntry> entry(List<TreeEntry> entries, String path) {
        return entries.stream().filter(e -> e.path().equals(path)).findFirst();
    }

    private static String message(Throwable e) {
        return e == null ? "errore sconosciuto" : Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
    }

    private static URI uri(String text) {
        return URI.create(text);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String join(String folder, String file) {
        return folder.isEmpty() ? file : folder + "/" + file;
    }

    private static String last(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** A string field, or -- with a null field -- the whole node when it is a string; null otherwise. */
    private static String text(JsonNode node, String field) {
        JsonNode value = field == null ? node : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() || value.isContainer() ? null : value.asString();
    }

    Set<String> cachedRepositories() {
        return trees.keySet();
    }

    /** Forgets every repository tree and frontmatter read so far. */
    void clearCaches() {
        trees.clear();
        frontmatters.clear();
        extensions.clearCaches();
    }
}
