package com.aicompany.backend.harness.hub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Where each application on this machine keeps its skills and knowledge
 * (ADR-036 §2). Every path is computed here, from the user's home and the
 * roaming application-data folder -- never taken from a request -- and the Hub
 * reads only below these roots and writes only below the {@link Location#writable()} ones.
 *
 * <p>The applications own most of these folders: plugins, built-in skills and
 * Claude Desktop's account-managed cache are read, never written. The writable
 * ones are the personal skill folders each application documents for its user:
 * {@code ~/.claude/skills}, {@code ~/.agents/skills} (Codex, Gemini CLI, OpenCode),
 * {@code ~/.gemini/antigravity/skills} and the like.
 */
@Component
public class SkillLocations {

    /** What a skill is to the application that has it. */
    public enum Scope {
        /** An entry of AI Company OS's own library. */
        LIBRARY,
        /** Written by the user (or by the Hub) in the application's personal folder. */
        PERSONAL,
        /** Part of a plugin the application installed. */
        PLUGIN,
        /** Shipped with the application. */
        SYSTEM,
        /** A global instructions file: CLAUDE.md, AGENTS.md, GEMINI.md. */
        INSTRUCTIONS,
        /** A knowledge item the application keeps. */
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
        /** {@code installed_plugins.json} and each plugin's install path, Claude Code's plugin store. */
        CLAUDE_CODE_PLUGINS,
        /** The manifests of Claude Desktop's skills and plugins, per account. */
        CLAUDE_DESKTOP,
        /** One Markdown file. */
        SINGLE_FILE,
        /** Knowledge items: one folder each, with Markdown or a metadata file. */
        KNOWLEDGE_ITEMS
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
    private final List<App> apps;
    private final List<Location> locations;

    public SkillLocations(@Value("${aicos.hub.home:${user.home}}") String home,
                          @Value("${aicos.hub.app-data:}") String appData) {
        this.home = Path.of(home).toAbsolutePath().normalize();
        this.appData = appData == null || appData.isBlank()
                ? Optional.ofNullable(System.getenv("APPDATA")).map(Path::of)
                        .orElse(this.home.resolve("AppData").resolve("Roaming")).toAbsolutePath().normalize()
                : Path.of(appData).toAbsolutePath().normalize();
        Path claude = this.home.resolve(".claude");
        Path codex = this.home.resolve(".codex");
        Path agents = this.home.resolve(".agents");
        Path gemini = this.home.resolve(".gemini");
        Path antigravity = gemini.resolve("antigravity");
        Path antigravityIde = gemini.resolve("antigravity-ide");
        Path opencode = this.home.resolve(".config").resolve("opencode");
        Path desktop = this.appData.resolve("Claude");

        this.apps = List.of(
                new App("claude-code", "Claude Code", "La CLI e la scheda Code dell'app Claude",
                        "claude-code", List.of("claude-code"), List.of(claude)),
                new App("claude-desktop", "Claude Desktop", "L'app Claude: skill e plugin del tuo account",
                        "claude-desktop", List.of(), List.of(desktop.resolve("local-agent-mode-sessions"))),
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
                        "verdent", List.of("verdent"), List.of(this.home.resolve(".verdent"))),
                new App("copilot", "GitHub Copilot CLI", "La CLI di GitHub Copilot",
                        null, List.of(), List.of(this.home.resolve(".copilot"))),
                new App("cursor", "Cursor", "L'editor Cursor",
                        null, List.of(), List.of(this.home.resolve(".cursor"))));

        this.locations = List.of(
                new Location("claude-code.personal", "claude-code", "Skill personali", claude.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("claude-code", "opencode")),
                new Location("claude-code.plugins", "claude-code", "Plugin", claude.resolve("plugins"),
                        Scope.PLUGIN, Layout.CLAUDE_CODE_PLUGINS, false, List.of("claude-code")),
                new Location("claude-code.instructions", "claude-code", "Istruzioni globali (CLAUDE.md)", claude.resolve("CLAUDE.md"),
                        Scope.INSTRUCTIONS, Layout.SINGLE_FILE, false, List.of("claude-code")),
                new Location("claude-desktop.account", "claude-desktop", "Skill e plugin dell'account",
                        desktop.resolve("local-agent-mode-sessions"), Scope.SYSTEM, Layout.CLAUDE_DESKTOP, false,
                        List.of("claude-desktop")),
                new Location("codex.personal", "codex", "Skill personali (~/.codex/skills)", codex.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, false, List.of("codex")),
                new Location("codex.system", "codex", "Skill di sistema", codex.resolve("skills").resolve(".system"),
                        Scope.SYSTEM, Layout.SKILL_FOLDERS, false, List.of("codex")),
                new Location("codex.plugins", "codex", "Plugin", codex.resolve("plugins").resolve("cache"),
                        Scope.PLUGIN, Layout.CODEX_PLUGINS, false, List.of("codex")),
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
                new Location("antigravity.knowledge", "antigravity", "Knowledge items", antigravity.resolve("knowledge"),
                        Scope.KNOWLEDGE, Layout.KNOWLEDGE_ITEMS, false, List.of("antigravity")),
                new Location("gemini-cli.personal", "gemini-cli", "Skill personali", gemini.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("gemini-cli")),
                new Location("gemini-cli.extensions", "gemini-cli", "Estensioni", gemini.resolve("extensions"),
                        Scope.PLUGIN, Layout.PLUGIN_SKILLS, false, List.of("gemini-cli")),
                new Location("gemini-cli.instructions", "gemini-cli", "Istruzioni globali (GEMINI.md)", gemini.resolve("GEMINI.md"),
                        Scope.INSTRUCTIONS, Layout.SINGLE_FILE, false, List.of("gemini-cli")),
                new Location("opencode.personal", "opencode", "Skill personali", opencode.resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("opencode")),
                new Location("opencode.legacy", "opencode", "Skill personali (cartella skill/)", opencode.resolve("skill"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, false, List.of("opencode")),
                new Location("opencode.instructions", "opencode", "Istruzioni globali (AGENTS.md)", opencode.resolve("AGENTS.md"),
                        Scope.INSTRUCTIONS, Layout.SINGLE_FILE, false, List.of("opencode")),
                new Location("verdent.personal", "verdent", "Skill", this.home.resolve(".verdent").resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("verdent")),
                new Location("copilot.personal", "copilot", "Skill personali", this.home.resolve(".copilot").resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("copilot")),
                new Location("cursor.personal", "cursor", "Skill personali", this.home.resolve(".cursor").resolve("skills"),
                        Scope.PERSONAL, Layout.SKILL_FOLDERS, true, List.of("cursor")));
    }

    public Path home() {
        return home;
    }

    public List<App> apps() {
        return apps;
    }

    public List<Location> locations() {
        return locations;
    }

    public App app(String id) {
        return apps.stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> HubProblemException.notFound("No application '" + id + "'"));
    }

    public Location location(String id) {
        return locations.stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> HubProblemException.notFound("No skill location '" + id + "'"));
    }

    /**
     * Where the Hub installs a library skill for an application: its writable
     * personal folder, when the application is on this machine.
     */
    public Optional<Location> deployTarget(String appId) {
        return apps.stream().filter(a -> a.id().equals(appId)).filter(App::detected).findFirst()
                .flatMap(app -> locations.stream()
                        .filter(l -> l.app().equals(app.id()) && l.writable() && l.scope() == Scope.PERSONAL)
                        .findFirst());
    }
}
