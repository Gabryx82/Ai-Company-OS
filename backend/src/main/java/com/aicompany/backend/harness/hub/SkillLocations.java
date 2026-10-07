package com.aicompany.backend.harness.hub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where each application on this machine keeps its skills, plugins, connectors
 * and knowledge (ADR-036 §2). Every path is computed here, from the user's home,
 * the roaming and the local application-data folders -- never taken from a
 * request -- and the Hub reads only below these roots and writes only below the
 * {@link Location#writable()} ones.
 *
 * <p>The applications own most of these folders: plugins, built-in skills and
 * Claude Desktop's account-managed cache are read, never written. The writable
 * ones are the personal skill folders each application documents for its user:
 * {@code ~/.claude/skills}, {@code ~/.agents/skills} (Codex, Gemini CLI, OpenCode),
 * {@code ~/.gemini/antigravity/skills} and the like.
 *
 * <p><strong>Claude Desktop from the Microsoft Store</strong> (ADR-036 §2, PHASE 31
 * review): an MSIX package's {@code %APPDATA%} is virtualised. The app -- and every
 * process it starts -- sees {@code %APPDATA%\Claude}; any other process finds its
 * data under {@code %LOCALAPPDATA%\Packages\Claude_<publisher>\LocalCache\Roaming\Claude}.
 * The control plane runs outside the package, so both places are candidates and
 * the one Claude Desktop wrote last is used.
 */
@Component
public class SkillLocations {

    /** What an item is to the application that has it. */
    public enum Scope {
        /** An entry of AI Company OS's own library. */
        LIBRARY,
        /** Written or configured by the user (or by the Hub) for the application. */
        PERSONAL,
        /** Part of a plugin the application installed. */
        PLUGIN,
        /** Shipped with the application. */
        SYSTEM,
        /** Comes with the user's account (a claude.ai connector). */
        ACCOUNT,
        /** A global instructions file: CLAUDE.md, AGENTS.md, GEMINI.md. */
        INSTRUCTIONS,
        /** A knowledge item or a memory the application keeps. */
        KNOWLEDGE
    }

    /** How a location is laid out on disk. */
    public enum Layout {
        /** {@code <root>/<name>/SKILL.md} */
        SKILL_FOLDERS,
        /** {@code <root>/<plugin>/.../skills/<name>/SKILL.md} */
        PLUGIN_SKILLS,
        /** {@code <root>/<marketplace>/<plugin>/<version>/skills/<name>/SKILL.md}, the Codex plugin cache. */
        CODEX_PLUGINS,
        /** {@code installed_plugins.json}, {@code settings.json} and each plugin's folder: Claude Code's plugin store. */
        CLAUDE_CODE_PLUGINS,
        /** The manifests of Claude Desktop's skills and plugins, per account. */
        CLAUDE_DESKTOP,
        /** One Markdown file. */
        SINGLE_FILE,
        /** Knowledge items: one folder each, with Markdown or a metadata file. */
        KNOWLEDGE_ITEMS,
        /** {@code ~/.claude/projects/<project>/memory}: what Claude Code remembers, per project. */
        CLAUDE_CODE_MEMORY,
        /** The application's MCP servers: its own configuration, and those its plugins bring. */
        CONNECTORS
    }

    /**
     * An application that has skills. {@code targets} are the execution targets
     * (ADR-025) whose agents work through it, {@code software} its Software Hub key.
     */
    public record App(String id, String name, String description, String software, List<String> targets,
                      List<Path> detectedBy) {
        public boolean detected() {
            return detectedBy.stream().anyMatch(Files::exists);
        }
    }

    public record Location(String id, String app, String label, Path root, Scope scope, Layout layout,
                           boolean writable, List<String> readers) {
    }

    private final Path home;
    private final Path appData;
    private final Path localAppData;

    public SkillLocations(@Value("${aicos.hub.home:${user.home}}") String home,
                          @Value("${aicos.hub.app-data:}") String appData,
                          @Value("${aicos.hub.local-app-data:}") String localAppData) {
        this.home = Path.of(home).toAbsolutePath().normalize();
        this.appData = appData == null || appData.isBlank()
                ? Optional.ofNullable(System.getenv("APPDATA")).map(Path::of)
                        .orElse(this.home.resolve("AppData").resolve("Roaming")).toAbsolutePath().normalize()
                : Path.of(appData).toAbsolutePath().normalize();
        this.localAppData = localAppData == null || localAppData.isBlank()
                ? Optional.ofNullable(System.getenv("LOCALAPPDATA")).map(Path::of)
                        .orElse(this.home.resolve("AppData").resolve("Local")).toAbsolutePath().normalize()
                : Path.of(localAppData).toAbsolutePath().normalize();
    }

    public Path home() {
        return home;
    }

    /**
     * Claude Desktop's data folder: {@code %APPDATA%\Claude}, or the Microsoft
     * Store package's own copy of it -- whichever Claude Desktop wrote last.
     */
    public Path claudeDesktopHome() {
        List<Path> candidates = claudeDesktopCandidates();
        return candidates.stream().filter(p -> Files.isDirectory(p.resolve("local-agent-mode-sessions")))
                .max(Comparator.comparingLong(SkillLocations::freshness))
                .or(() -> candidates.stream().filter(Files::isDirectory).findFirst())
                .orElse(candidates.getFirst());
    }

    /** {@code %APPDATA%\Claude} first, then each {@code Packages\Claude_*} folder's virtualised copy. */
    List<Path> claudeDesktopCandidates() {
        List<Path> out = new ArrayList<>();
        out.add(appData.resolve("Claude"));
        Path packages = localAppData.resolve("Packages");
        if (Files.isDirectory(packages, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> list = Files.list(packages)) {
                list.filter(p -> p.getFileName().toString().startsWith("Claude_"))
                        .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .map(p -> p.resolve("LocalCache").resolve("Roaming").resolve("Claude"))
                        .forEach(out::add);
            } catch (IOException e) {
                // only %APPDATA%\Claude, then
            }
        }
        return out;
    }

    public List<App> apps() {
        Path claude = home.resolve(".claude");
        Path codex = home.resolve(".codex");
        Path agents = home.resolve(".agents");
        Path gemini = home.resolve(".gemini");
        Path antigravity = gemini.resolve("antigravity");
        Path antigravityIde = gemini.resolve("antigravity-ide");
        Path opencode = home.resolve(".config").resolve("opencode");
        return List.of(
                new App("claude-code", "Claude Code",
                        "La CLI e la scheda Code dell'app Claude (che riceve anche i plugin e le skill di Claude Desktop)",
                        "claude-code", List.of("claude-code"), List.of(claude)),
                new App("claude-desktop", "Claude Desktop", "L'app Claude: skill, plugin e connettori del tuo account",
                        "claude-desktop", List.of(),
                        claudeDesktopCandidates().stream().map(p -> p.resolve("local-agent-mode-sessions")).toList()),
                new App("codex", "Codex · ChatGPT", "L'app e la CLI Codex di OpenAI",
                        "codex", List.of("codex"), List.of(codex)),
                new App("agents", "Agent Skills condivise", "~/.agents/skills: la cartella standard che leggono Codex, Gemini CLI, OpenCode e altri",
                        null, List.of("codex", "opencode"), List.of(agents, codex, gemini.resolve("settings.json"))),
                new App("antigravity", "Antigravity", "Antigravity (agent manager) e Antigravity IDE di Google",
                        "antigravity", List.of("antigravity", "antigravity-ide"), List.of(antigravity, antigravityIde)),
                new App("gemini-cli", "Gemini CLI", "La CLI Gemini di Google",
                        null, List.of(), List.of(gemini.resolve("settings.json"), gemini.resolve("skills"), gemini.resolve("extensions"))),
                new App("opencode", "OpenCode", "La CLI OpenCode",
                        "opencode", List.of("opencode"), List.of(opencode)),
                new App("verdent", "Verdent", "L'app Verdent",
                        "verdent", List.of("verdent"), List.of(home.resolve(".verdent"))),
                new App("copilot", "GitHub Copilot CLI", "La CLI di GitHub Copilot",
                        null, List.of(), List.of(home.resolve(".copilot"))),
                new App("cursor", "Cursor", "L'editor Cursor",
                        null, List.of(), List.of(home.resolve(".cursor"))));
    }

    public List<Location> locations() {
        Path claude = home.resolve(".claude");
        Path codex = home.resolve(".codex");
        Path agents = home.resolve(".agents");
        Path gemini = home.resolve(".gemini");
        Path antigravity = gemini.resolve("antigravity");
        Path antigravityIde = gemini.resolve("antigravity-ide");
        Path opencode = home.resolve(".config").resolve("opencode");
        Path desktop = claudeDesktopHome();
        return List.of(
                new Location("claude-code.personal", "claude-code", "Skill personali", claude.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("claude-code", "opencode")),
                new Location("claude-code.plugins", "claude-code", "Plugin", claude.resolve("plugins"),
                        Scope.PLUGIN, Layout.CLAUDE_CODE_PLUGINS, false, List.of("claude-code")),
                new Location("claude-code.connectors", "claude-code", "Connettori (MCP)", home.resolve(".claude.json"),
                        Scope.PERSONAL, Layout.CONNECTORS, false, List.of("claude-code")),
                new Location("claude-code.instructions", "claude-code", "Istruzioni globali (CLAUDE.md)", claude.resolve("CLAUDE.md"),
                        Scope.INSTRUCTIONS, Layout.SINGLE_FILE, false, List.of("claude-code")),
                new Location("claude-code.memory", "claude-code", "Memoria dei progetti", claude.resolve("projects"),
                        Scope.KNOWLEDGE, Layout.CLAUDE_CODE_MEMORY, false, List.of("claude-code")),
                new Location("claude-desktop.account", "claude-desktop", "Skill e plugin dell'account",
                        desktop.resolve("local-agent-mode-sessions"), Scope.SYSTEM, Layout.CLAUDE_DESKTOP, false,
                        List.of("claude-desktop")),
                new Location("claude-desktop.connectors", "claude-desktop", "Connettori (MCP) ed estensioni",
                        desktop.resolve("extensions-installations.json"), Scope.PERSONAL, Layout.CONNECTORS, false,
                        List.of("claude-desktop")),
                new Location("codex.personal", "codex", "Skill personali (~/.codex/skills)", codex.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, false, List.of("codex")),
                new Location("codex.system", "codex", "Skill di sistema", codex.resolve("skills").resolve(".system"),
                        Scope.SYSTEM, Layout.SKILL_FOLDERS, false, List.of("codex")),
                new Location("codex.plugins", "codex", "Plugin", codex.resolve("plugins").resolve("cache"),
                        Scope.PLUGIN, Layout.CODEX_PLUGINS, false, List.of("codex")),
                new Location("codex.connectors", "codex", "Connettori (MCP)", codex.resolve("config.toml"),
                        Scope.PERSONAL, Layout.CONNECTORS, false, List.of("codex")),
                new Location("codex.instructions", "codex", "Istruzioni globali (AGENTS.md)", codex.resolve("AGENTS.md"),
                        Scope.INSTRUCTIONS, Layout.SINGLE_FILE, false, List.of("codex")),
                new Location("agents.personal", "agents", "Skill personali condivise", agents.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("codex", "gemini-cli", "opencode", "copilot")),
                new Location("antigravity.personal", "antigravity", "Skill globali", antigravity.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("antigravity")),
                new Location("antigravity.system", "antigravity", "Skill integrate (agent manager)",
                        antigravity.resolve("builtin").resolve("skills"), Scope.SYSTEM, Layout.SKILL_FOLDERS, false,
                        List.of("antigravity")),
                new Location("antigravity.ide-system", "antigravity", "Skill integrate (IDE)",
                        antigravityIde.resolve("builtin").resolve("skills"), Scope.SYSTEM, Layout.SKILL_FOLDERS, false,
                        List.of("antigravity")),
                new Location("antigravity.ide-plugins", "antigravity", "Plugin dell'IDE", antigravityIde.resolve("plugins"),
                        Scope.PLUGIN, Layout.PLUGIN_SKILLS, false, List.of("antigravity")),
                new Location("antigravity.connectors", "antigravity", "Connettori (MCP)",
                        gemini.resolve("config").resolve("mcp_config.json"), Scope.PERSONAL, Layout.CONNECTORS, false,
                        List.of("antigravity")),
                new Location("antigravity.knowledge", "antigravity", "Knowledge items", antigravity.resolve("knowledge"),
                        Scope.KNOWLEDGE, Layout.KNOWLEDGE_ITEMS, false, List.of("antigravity")),
                new Location("gemini-cli.personal", "gemini-cli", "Skill personali", gemini.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("gemini-cli")),
                new Location("gemini-cli.extensions", "gemini-cli", "Estensioni", gemini.resolve("extensions"),
                        Scope.PLUGIN, Layout.PLUGIN_SKILLS, false, List.of("gemini-cli")),
                new Location("gemini-cli.connectors", "gemini-cli", "Connettori (MCP)", gemini.resolve("settings.json"),
                        Scope.PERSONAL, Layout.CONNECTORS, false, List.of("gemini-cli")),
                new Location("gemini-cli.instructions", "gemini-cli", "Istruzioni globali (GEMINI.md)", gemini.resolve("GEMINI.md"),
                        Scope.INSTRUCTIONS, Layout.SINGLE_FILE, false, List.of("gemini-cli")),
                new Location("opencode.personal", "opencode", "Skill personali", opencode.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("opencode")),
                new Location("opencode.legacy", "opencode", "Skill personali (cartella skill/)", opencode.resolve("skill"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, false, List.of("opencode")),
                new Location("opencode.instructions", "opencode", "Istruzioni globali (AGENTS.md)", opencode.resolve("AGENTS.md"),
                        Scope.INSTRUCTIONS, Layout.SINGLE_FILE, false, List.of("opencode")),
                new Location("verdent.personal", "verdent", "Skill", home.resolve(".verdent").resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("verdent")),
                new Location("copilot.personal", "copilot", "Skill personali", home.resolve(".copilot").resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("copilot")),
                new Location("cursor.personal", "cursor", "Skill personali", home.resolve(".cursor").resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("cursor")));
    }

    public App app(String id) {
        return apps().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> HubProblemException.notFound("No application '" + id + "'"));
    }

    public Location location(String id) {
        return locations().stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> HubProblemException.notFound("No skill location '" + id + "'"));
    }

    /**
     * Where the Hub installs a library skill for an application: its writable
     * personal folder, when the application is on this machine.
     */
    public Optional<Location> deployTarget(String appId) {
        return apps().stream().filter(a -> a.id().equals(appId)).filter(App::detected).findFirst()
                .flatMap(app -> locations().stream()
                        .filter(l -> l.app().equals(app.id()) && l.writable() && l.scope() == Scope.PERSONAL)
                        .findFirst());
    }

    /** When Claude Desktop last wrote its manifests below a candidate folder; 0 when it never did. */
    private static long freshness(Path desktopHome) {
        Path sessions = desktopHome.resolve("local-agent-mode-sessions");
        try (Stream<Path> walk = Files.walk(sessions, 4)) {
            return walk.filter(p -> p.getFileName().toString().equals("manifest.json"))
                    .mapToLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).max().orElse(0L);
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0L;
        }
    }
}
