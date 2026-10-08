package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.hub.SkillHubService.CopyStatus;
import com.aicompany.backend.harness.hub.SkillHubService.SkillView;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything that can be brought up to date, in one list (ADR-036 §8):
 * <ul>
 *   <li>library skills installed from a marketplace whose package changed upstream;</li>
 *   <li>library skills imported from an application whose source changed there;</li>
 *   <li>copies in the applications the library has moved past (push), or that were
 *       changed inside the application (pull into the library);</li>
 *   <li>plugins of Claude Desktop and Claude Code with a newer published version --
 *       these the application updates itself; the Hub says so and opens it.</li>
 * </ul>
 * What the Hub applies, it applies one item at a time, each in its own transaction.
 */
@Service
public class SkillUpdates {

    private static final Logger LOG = LoggerFactory.getLogger(SkillUpdates.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern GITHUB_URL = Pattern.compile("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(\\.git)?/?$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{7,40}$");

    /** {@code CONNECTOR}: PHASE 32 (TD-90), a connector the Hub installed, newer in its catalog. */
    public enum Type { MARKETPLACE, SOURCE, COPY, PULL, PLUGIN, CONNECTOR }

    /**
     * One thing that can be updated. {@code applicable} items the Hub applies itself;
     * the others say where to do it ({@code software} to open, a {@code command}).
     */
    public record Item(String id, Type type, String app, String name, String key, @Nullable String from, @Nullable String to,
                       String detail, boolean applicable, boolean needsOverwrite, @Nullable String software, @Nullable String command) {
    }

    public record UpdatesView(@Nullable Instant checkedAt, List<Item> items, List<String> errors) {
    }

    public record Applied(String id, String outcome, String message) {
    }

    private final SkillHubService hub;
    private final SkillScanner scanner;
    private final SkillLocations locations;
    private final HubWeb web;
    private volatile List<Item> plugins = List.of();
    private volatile List<String> errors = List.of();
    private volatile Instant checkedAt;

    private final HubInstaller installer;

    public SkillUpdates(SkillHubService hub, SkillScanner scanner, SkillLocations locations, HubWeb web, HubInstaller installer) {
        this.installer = installer;
        this.hub = hub;
        this.scanner = scanner;
        this.locations = locations;
        this.web = web;
    }

    /** What is known now: the last check's upstream answers, and the copies as they stand on disk. */
    public UpdatesView view() {
        SkillHubService.InstalledView installed = hub.installed(false);
        List<Item> items = new ArrayList<>();
        for (SkillView skill : installed.skills()) {
            if (skill.provenance() != null && skill.provenance().updateAvailable()) {
                boolean fromApp = SkillInstallation.APP.equals(skill.provenance().provider());
                items.add(new Item((fromApp ? "src:" : "mkt:") + skill.libraryKey(), fromApp ? Type.SOURCE : Type.MARKETPLACE,
                        SkillHubService.LIBRARY_APP, skill.name(), skill.libraryKey(), skill.provenance().installedVersion(),
                        skill.provenance().latestVersion(),
                        fromApp ? "È cambiata in " + skill.provenance().providerName().replace("App: ", "") + " dopo l'importazione"
                                : "Nuova versione su " + skill.provenance().providerName(),
                        true, skill.provenance().localChanges(), null, null));
            }
            if (skill.copyStatus() == null || skill.copyStatus() == CopyStatus.IN_SYNC) {
                continue;
            }
            String appName = appName(skill.app());
            switch (skill.copyStatus()) {
                case OUTDATED -> items.add(new Item("push:" + skill.id(), Type.COPY, skill.app(), skill.name(), skill.libraryKey(),
                        null, null, "La libreria è più recente della copia in " + appName, true, false, null, null));
                case MODIFIED -> items.add(new Item("pull:" + skill.id(), Type.PULL, skill.app(), skill.name(), skill.libraryKey(),
                        null, null, "Modificata in " + appName + ": riportala nella libreria (le altre copie seguono)",
                        true, false, null, null));
                case CONFLICT -> {
                    items.add(new Item("pull:" + skill.id(), Type.PULL, skill.app(), skill.name(), skill.libraryKey(), null, null,
                            "Cambiata sia in " + appName + " sia nella libreria: tieni la versione dell'app", true, true, null, null));
                    items.add(new Item("push:" + skill.id(), Type.COPY, skill.app(), skill.name(), skill.libraryKey(), null, null,
                            "Cambiata sia in " + appName + " sia nella libreria: tieni la versione della libreria", true, true,
                            null, null));
                }
                case ORPHAN -> items.add(new Item("orphan:" + skill.id(), Type.COPY, skill.app(), skill.name(),
                        skill.libraryKey(), null, null, "La libreria non ha più questa skill: la copia in " + appName
                        + " resta finché non la togli", false, false, null, null));
                default -> {
                }
            }
        }
        items.addAll(plugins);
        return new UpdatesView(checkedAt, items, errors);
    }

    /** Asks every upstream now: marketplaces, sources in the applications, published plugin versions. */
    public synchronized UpdatesView check() {
        scanner.scan();
        List<String> problems = new ArrayList<>(hub.checkProvenance());
        List<Item> found = new ArrayList<>();
        try {
            found.addAll(claudeDesktopPlugins());
        } catch (RuntimeException e) {
            problems.add("Plugin di Claude Desktop: " + e.getMessage());
        }
        try {
            found.addAll(claudeCodePlugins());
        } catch (RuntimeException e) {
            problems.add("Plugin di Claude Code: " + e.getMessage());
        }
        // PHASE 32 (TD-90): the connectors the Hub installed from a catalog.
        for (HubInstaller.ConnectorUpdate update : installer.connectorUpdates(problems)) {
            found.add(new Item("conn:" + update.app() + ":" + update.name(), Type.CONNECTOR, update.app(), update.name(),
                    update.reference(), update.from(), update.to(), update.applicable()
                    ? "Nuova versione nel registro MCP: si reinstalla al suo posto"
                    : "Nuova versione nel registro MCP: reinstallalo dal Marketplace (servono di nuovo i suoi valori, che non vengono conservati)",
                    update.applicable(), false, null, null));
        }
        plugins = List.copyOf(found);
        errors = List.copyOf(problems);
        checkedAt = Instant.now();
        return view();
    }

    /** Applies one item; each call is its own transaction. */
    public Applied apply(String id, boolean overwrite) {
        try {
            String message;
            if (id.startsWith("mkt:") || id.startsWith("src:")) {
                message = hub.update(id.substring(4), overwrite);
            } else if (id.startsWith("conn:") && id.split(":", 3).length == 3) {
                String[] parts = id.split(":", 3);
                HubInstaller.InstallOutcome outcome = installer.reinstallConnector(parts[1], parts[2]);
                if (outcome.status() != HubInstaller.InstallStatus.INSTALLED) {
                    return new Applied(id, "FAILED", outcome.message());
                }
                message = outcome.message();
            } else if (id.startsWith("pull:")) {
                message = hub.pull(id.substring(5), overwrite);
            } else if (id.startsWith("push:")) {
                FoundSkill copy = scanner.find(id.substring(5))
                        .orElseThrow(() -> HubProblemException.notFound("La copia non c'è più"));
                if (copy.marker() == null) {
                    throw HubProblemException.notFound("Non è una copia del Knowledge Hub");
                }
                message = hub.deploy(copy.marker().key(), copy.app(), overwrite).message();
            } else {
                return new Applied(id, "SKIPPED", "Questo aggiornamento si fa dall'applicazione");
            }
            return new Applied(id, "UPDATED", message);
        } catch (HubProblemException e) {
            return new Applied(id, e.problem() == com.aicompany.backend.api.ApiProblem.SKILL_HUB_CONFLICT ? "CONFLICT" : "FAILED",
                    e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Update {} failed", id, e);
            return new Applied(id, "FAILED", e.getMessage());
        }
    }

    // --- plugins --------------------------------------------------------------------------

    /**
     * Claude Desktop's plugins come from Anthropic's knowledge-work-plugins
     * marketplace: its {@code marketplace.json} says where each plugin lives, and
     * that plugin's {@code plugin.json} its published version.
     */
    private List<Item> claudeDesktopPlugins() {
        Map<String, String> installed = installedPlugins("claude-desktop", "knowledge-work-plugins");
        if (installed.isEmpty()) {
            return List.of();
        }
        JsonNode marketplace = json(raw("anthropics", "knowledge-work-plugins", "HEAD", ".claude-plugin/marketplace.json"));
        List<Item> out = new ArrayList<>();
        for (JsonNode plugin : marketplace.path("plugins")) {
            String name = text(plugin, "name");
            if (name == null || !installed.containsKey(name)) {
                continue;
            }
            Optional<URI> manifest = pluginManifest(plugin.path("source"));
            if (manifest.isEmpty()) {
                continue;
            }
            try {
                String published = text(json(manifest.get()), "version");
                String current = installed.get(name);
                if (published != null && current != null && newer(published, current)) {
                    out.add(new Item("plugin:claude-desktop:" + name, Type.PLUGIN, "claude-desktop", name, null, current,
                            published, "Claude Desktop aggiorna i plugin da sé; se tarda: Personalizza → Plugin",
                            false, false, "claude-desktop", null));
                }
            } catch (RuntimeException e) {
                LOG.debug("No published version for {}", name, e);
            }
        }
        return out;
    }

    /** Claude Code's plugins against the marketplaces it cloned locally; only a strictly newer version counts. */
    private List<Item> claudeCodePlugins() {
        List<Item> out = new ArrayList<>();
        Map<String, String[]> installed = new LinkedHashMap<>();
        for (FoundSkill skill : scanner.last().skills()) {
            if (skill.location().equals("claude-code.plugins") && skill.plugin() != null && skill.marketplace() != null) {
                installed.putIfAbsent(skill.plugin() + "@" + skill.marketplace(),
                        new String[]{skill.plugin(), skill.marketplace(), skill.pluginVersion()});
            }
        }
        Path marketplaces = locations.location("claude-code.plugins").root().resolve("marketplaces");
        for (String[] plugin : installed.values()) {
            if (!SkillMarketplaces.OWNER.matcher(plugin[1]).matches()) {
                continue;
            }
            Path root = marketplaces.resolve(plugin[1]);
            Optional<JsonNode> index = SkillScanner.json(root.resolve(".claude-plugin").resolve("marketplace.json"));
            if (index.isEmpty()) {
                continue;
            }
            for (JsonNode entry : index.get().path("plugins")) {
                if (!plugin[0].equals(text(entry, "name"))) {
                    continue;
                }
                String published = text(entry, "version");
                String source = text(entry, "source");
                if (published == null && source != null && source.startsWith("./") && PackagePaths.valid(source.substring(2))) {
                    published = SkillScanner.json(root.resolve(source.substring(2)).resolve(".claude-plugin").resolve("plugin.json"))
                            .map(j -> text(j, "version")).orElse(null);
                }
                if (published != null && plugin[2] != null && newer(published, plugin[2])) {
                    out.add(new Item("plugin:claude-code:" + plugin[0], Type.PLUGIN, "claude-code", plugin[0], null,
                            plugin[2], published, "Si aggiorna con la CLI di Claude Code", false, false, "claude-code",
                            "claude plugin update " + plugin[0] + "@" + plugin[1]));
                }
            }
        }
        return out;
    }

    private Map<String, String> installedPlugins(String app, String marketplace) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FoundSkill skill : scanner.last().skills()) {
            if (skill.app().equals(app) && skill.scope() == SkillLocations.Scope.PLUGIN && skill.plugin() != null
                    && marketplace.equals(skill.marketplace())) {
                out.putIfAbsent(skill.plugin(), skill.pluginVersion());
            }
        }
        return out;
    }

    /** {@code "./name"}, or a GitHub {@code url}/{@code git-subdir} source pinned by sha or ref. */
    private Optional<URI> pluginManifest(JsonNode source) {
        if (source.isString()) {
            String path = source.asString();
            if (path.startsWith("./") && PackagePaths.valid(path.substring(2))) {
                return Optional.of(raw("anthropics", "knowledge-work-plugins", "HEAD", path.substring(2) + "/.claude-plugin/plugin.json"));
            }
            return Optional.empty();
        }
        String url = text(source, "url");
        Matcher github = url == null ? null : GITHUB_URL.matcher(url);
        if (github == null || !github.matches()) {
            return Optional.empty();
        }
        String ref = Optional.ofNullable(text(source, "sha")).filter(s -> SHA.matcher(s).matches())
                .orElse(Optional.ofNullable(text(source, "ref")).filter(r -> r.matches("[A-Za-z0-9._/-]{1,100}")).orElse("HEAD"));
        String sub = text(source, "path");
        if (sub != null && !sub.isBlank() && !PackagePaths.valid(sub)) {
            return Optional.empty();
        }
        String path = (sub == null || sub.isBlank() ? "" : sub + "/") + ".claude-plugin/plugin.json";
        return Optional.of(raw(github.group(1), github.group(2), ref, path));
    }

    private static URI raw(String owner, String repo, String ref, String path) {
        try {
            return new URI("https", "raw.githubusercontent.com", "/" + owner + "/" + repo + "/" + ref + "/" + path, null);
        } catch (URISyntaxException e) {
            throw HubProblemException.packageRefused("Not a valid path: " + path);
        }
    }

    private JsonNode json(URI uri) {
        HubWeb.Response response = web.get(uri, 2 * 1024 * 1024);
        if (!response.ok()) {
            throw HubProblemException.marketplace(uri.getHost() + " answered HTTP " + response.status());
        }
        try {
            return JSON.readTree(response.text());
        } catch (RuntimeException e) {
            throw HubProblemException.marketplace("The answer is not JSON");
        }
    }

    /** Whether {@code candidate} is a strictly later version than {@code current} (numbers first, a pre-release before its release). */
    static boolean newer(String candidate, String current) {
        int[] a = numbers(candidate);
        int[] b = numbers(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        boolean aPre = candidate.contains("-");
        boolean bPre = current.contains("-");
        return bPre && !aPre;
    }

    private static int[] numbers(String version) {
        int[] out = new int[3];
        String core = version.strip().replaceFirst("^[vV]", "").split("[-+]")[0];
        String[] parts = core.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private String appName(String app) {
        return locations.apps().stream().filter(a -> a.id().equals(app)).map(SkillLocations.App::name).findFirst().orElse(app);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.isContainer() ? null : value.asString();
    }
}
