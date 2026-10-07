package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import com.aicompany.backend.support.FakeHubWeb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 31 (ADR-036): the Knowledge Hub across applications and marketplaces --
 * what it finds on the machine, what a synchronisation says changed, importing,
 * installing, copies kept in step, updates, and what it refuses to overwrite.
 * The applications live in a fake home; the marketplaces are a {@link FakeHubWeb}.
 */
class SkillHubApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillLocations locations;

    @Autowired
    private SkillLibrary library;

    @Autowired
    private SkillMarketplaces markets;

    @Autowired
    private SkillScanner scanner;

    @Autowired
    private FakeHubWeb web;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${aicos.home}")
    private String aicosHome;

    @Value("${aicos.hub.local-app-data}")
    private String localAppData;

    private final JsonMapper json = JsonMapper.builder().build();

    private Path home;
    private Path desktop;

    @BeforeEach
    @AfterEach
    void aCleanMachine() throws IOException {
        web.reset();
        markets.clearCaches();
        jdbc.update("DELETE FROM skill_sync_events");
        jdbc.update("DELETE FROM skill_snapshot");
        jdbc.update("DELETE FROM skill_installations");
        jdbc.update("DELETE FROM agent_resources");
        jdbc.update("DELETE FROM harness_resources WHERE origin <> 'CATALOG'");
        jdbc.update("UPDATE harness_resources SET file_path = NULL, file_synced_at = NULL");
        home = locations.home();
        // Claude Desktop from the Microsoft Store: its data is only in the package's virtualised AppData.
        desktop = Path.of(localAppData).resolve("Packages/Claude_test1234/LocalCache/Roaming/Claude/local-agent-mode-sessions");
        for (Path folder : List.of(home, locations.claudeDesktopCandidates().getFirst().getParent(), Path.of(localAppData),
                library.root(), Path.of(aicosHome).resolve("backups"))) {
            deleteTree(folder);
        }
        Files.createDirectories(home);
        scanner.scan();
    }

    // --- what is on the machine -----------------------------------------------------------

    @Test
    void everyApplicationsSkillsAreListedByApplicationAndTypeNextToTheLibrarys() throws Exception {
        write(home.resolve(".claude/skills/commit-helper/SKILL.md"),
                "---\nname: commit-helper\ndescription: >-\n  Writes commit messages\n  that say why.\n---\n# Commit\n");
        write(home.resolve(".codex/skills/.system/imagegen/SKILL.md"), "---\nname: imagegen\ndescription: Images\n---\n");
        write(home.resolve(".codex/plugins/cache/openai-bundled/browser/1.2.0/skills/browser-use/SKILL.md"),
                "---\nname: browser-use\ndescription: Drive the browser\n---\n");
        write(home.resolve(".codex/config.toml"), "model = \"x\"\n\n[plugins.\"browser@openai-bundled\"]\nenabled = false\n");
        Path appPlugin = home.resolve(".codex/plugins/cache/openai-curated-remote/app-6a3293e1/2.1.0");
        write(appPlugin.resolve(".codex-plugin/plugin.json"),
                "{\"name\": \"app-6a3293e1\", \"version\": \"2.1.0\", \"interface\": {\"displayName\": \"Higgsfield\"}}");
        write(appPlugin.resolve("skills/make-video/SKILL.md"), "---\nname: make-video\ndescription: Videos\n---\n");
        write(home.resolve(".codex/AGENTS.md"), "# Regole\n\nRispondi in italiano.\n");
        Path account = desktop.resolve("skills-plugin/org1/acct1");
        write(account.resolve("manifest.json"), "{\"lastUpdated\": 2, \"skills\": ["
                + "{\"skillId\": \"pdf\", \"creatorType\": \"anthropic\", \"enabled\": true},"
                + "{\"skillId\": \"mine\", \"creatorType\": \"user\", \"enabled\": false}]}");
        write(account.resolve("skills/pdf/SKILL.md"), "---\nname: pdf\ndescription: Fill PDF forms\n---\n");
        write(account.resolve("skills/mine/SKILL.md"), "---\nname: mine\ndescription: My own skill\n---\n");
        Path old = desktop.resolve("skills-plugin/org2/acct2");
        write(old.resolve("manifest.json"), "{\"lastUpdated\": 1, \"skills\": [{\"skillId\": \"old\", \"creatorType\": \"user\"}]}");
        write(old.resolve("skills/old/SKILL.md"), "---\nname: old\ndescription: From another account\n---\n");
        Path rpm = desktop.resolve("acct1/org1/rpm");
        write(rpm.resolve("manifest.json"), "{\"lastUpdated\": 2, \"plugins\": [{\"id\": \"plugin_1\", \"name\": \"render\", "
                + "\"marketplaceName\": \"knowledge-work-plugins\"}]}");
        write(rpm.resolve("plugin_1/.claude-plugin/plugin.json"), "{\"name\": \"render\", \"version\": \"0.2.2\"}");
        write(rpm.resolve("plugin_1/skills/render-deploy/SKILL.md"), "---\nname: render-deploy\ndescription: Deploy on Render\n---\n");
        write(rpm.resolve("plugin_1/.mcp.json"), "{\"mcpServers\": {\"render\": {\"type\": \"http\", \"url\": \"https://mcp.render.com/mcp\"},"
                + " \"slack\": {\"type\": \"http\", \"url\": \"https://mcp.slack.com/mcp\"}}}");
        // A plugin made only of hooks: it has no skill, and it is still installed.
        write(rpm.resolve("manifest.json"), "{\"lastUpdated\": 2, \"plugins\": [{\"id\": \"plugin_1\", \"name\": \"render\", "
                + "\"marketplaceName\": \"knowledge-work-plugins\"}, {\"id\": \"plugin_2\", \"name\": \"security-guidance\", "
                + "\"marketplaceName\": \"knowledge-work-plugins\"}, {\"id\": \"plugin_3\", \"name\": \"sales\"}]}");
        write(rpm.resolve("plugin_2/.claude-plugin/plugin.json"), "{\"name\": \"security-guidance\", \"version\": \"2.0.8\", "
                + "\"description\": \"Security review\"}");
        write(rpm.resolve("plugin_2/hooks/hooks.json"), "{}");
        write(rpm.resolve("plugin_3/.claude-plugin/plugin.json"), "{\"name\": \"sales\", \"version\": \"2.0.1\"}");
        write(rpm.resolve("plugin_3/.mcp.json"), "{\"mcpServers\": {\"slack\": {\"type\": \"http\", \"url\": \"https://mcp.slack.com/mcp\"}}}");
        Path desktopHome = desktop.getParent();
        write(desktopHome.resolve("extensions-installations.json"), "{\"extensions\": {\"ant.dir.blender\": {\"id\": \"ant.dir.blender\","
                + " \"manifest\": {\"name\": \"Blender\", \"version\": \"1.0.1\", \"server\": {\"type\": \"uv\"}}}}}");
        write(desktopHome.resolve("Claude Extensions Settings/ant.dir.blender.json"), "{\"isEnabled\": false}");

        // Claude Code: a plugin with its components, its MCP servers, and the user's own servers -- one with a secret.
        Path ccPlugin = home.resolve(".claude/plugins/cache/claude-plugins-official/frontend-design/abc123");
        write(ccPlugin.resolve(".claude-plugin/plugin.json"), "{\"name\": \"frontend-design\", \"description\": \"Frontend design\", "
                + "\"author\": {\"name\": \"Anthropic\"}}");
        write(ccPlugin.resolve("skills/frontend-design/SKILL.md"), "---\nname: frontend-design\ndescription: Distinctive UI\n---\n");
        write(ccPlugin.resolve("commands/review.md"), "# Review\n");
        write(ccPlugin.resolve("agents/critic.md"), "# Critic\n");
        write(home.resolve(".claude/plugins/installed_plugins.json"), "{\"version\": 2, \"plugins\": {\"frontend-design@claude-plugins-official\": "
                + "[{\"scope\": \"user\", \"installPath\": " + json.writeValueAsString(ccPlugin.toString()) + ", \"version\": \"abc123\"}]}}");
        write(home.resolve(".claude/settings.json"), "{\"enabledPlugins\": {\"frontend-design@claude-plugins-official\": false}}");
        write(home.resolve(".claude.json"), "{\"oauthAccount\": {\"emailAddress\": \"someone@example.org\"}, \"mcpServers\": {"
                + "\"blender\": {\"type\": \"stdio\", \"command\": \"C:\\\\Tools\\\\uvx.EXE\", \"args\": [\"blender-mcp\", \"--token=SECRET-ARG-123\"],"
                + " \"env\": {\"BLENDER_API_KEY\": \"SECRET-ENV-456\"}},"
                + "\"remote\": {\"type\": \"http\", \"url\": \"https://user:pw@mcp.example.org/mcp?api_key=SECRET-QUERY-789\"}},"
                + "\"projects\": {\"C:/Work/Shop\": {\"mcpServers\": {}}},"
                + "\"claudeAiMcpEverConnected\": [\"claude.ai Claude Docs\"]}");
        write(home.resolve(".claude/projects/C--Work-Shop/memory/MEMORY.md"), "- [Prezzi](prezzi.md) — IVA inclusa\n");
        write(home.resolve(".claude/projects/C--Work-Shop/memory/prezzi.md"), "I prezzi si mostrano con l'IVA.\n");

        JsonNode view = getJson("/api/skill-hub/installed?rescan=true");

        assertThat(app(view, "claude-code").path("detected").asBoolean()).isTrue();
        assertThat(app(view, "cursor").path("detected").asBoolean()).isFalse();
        assertThat(app(view, "claude-code").path("installable").asBoolean()).isTrue();
        assertThat(app(view, "claude-desktop").path("installable").asBoolean()).isFalse();

        JsonNode commit = skill(view, "commit-helper");
        assertThat(commit.path("app").asString()).isEqualTo("claude-code");
        assertThat(commit.path("scope").asString()).isEqualTo("PERSONAL");
        assertThat(commit.path("writable").asBoolean()).isTrue();
        assertThat(commit.path("description").asString()).isEqualTo("Writes commit messages that say why.");

        assertThat(skill(view, "imagegen").path("scope").asString()).isEqualTo("SYSTEM");
        JsonNode browser = skill(view, "browser-use");
        assertThat(browser.path("scope").asString()).isEqualTo("PLUGIN");
        assertThat(browser.path("plugin").asString()).isEqualTo("browser");
        assertThat(browser.path("pluginVersion").asString()).isEqualTo("1.2.0");
        assertThat(browser.path("enabled").asBoolean()).isFalse();
        assertThat(browser.path("category").asString()).isEqualTo("browser");
        // A Codex app plugin's folder is an id; its manifest says what it is called.
        assertThat(skill(view, "make-video").path("plugin").asString()).isEqualTo("Higgsfield");

        assertThat(skill(view, "pdf").path("scope").asString()).isEqualTo("SYSTEM");
        assertThat(skill(view, "pdf").path("category").asString()).isEqualTo("documenti");
        assertThat(skill(view, "mine").path("scope").asString()).isEqualTo("PERSONAL");
        assertThat(skill(view, "mine").path("enabled").asBoolean()).isFalse();
        assertThat(names(view)).doesNotContain("old");
        JsonNode render = skill(view, "render-deploy");
        assertThat(render.path("pluginVersion").asString()).isEqualTo("0.2.2");
        assertThat(render.path("marketplace").asString()).isEqualTo("knowledge-work-plugins");
        assertThat(render.path("category").asString()).isEqualTo("devops");

        // Every plugin, even the one without skills, with what it is made of.
        JsonNode security = item(view, "PLUGIN", "security-guidance");
        assertThat(security.path("app").asString()).isEqualTo("claude-desktop");
        assertThat(security.path("details").path("Skill").asString()).isEqualTo("0");
        assertThat(security.path("details").path("Hook").asString()).isEqualTo("sì");
        assertThat(item(view, "PLUGIN", "render").path("details").path("Connettori").asString()).startsWith("2");
        JsonNode frontend = item(view, "PLUGIN", "frontend-design");
        assertThat(frontend.path("app").asString()).isEqualTo("claude-code");
        assertThat(frontend.path("enabled").asBoolean()).isFalse();
        assertThat(frontend.path("details").path("Comandi").asString()).isEqualTo("1");
        assertThat(frontend.path("details").path("Agenti").asString()).isEqualTo("1");
        assertThat(frontend.path("details").path("Autore").asString()).isEqualTo("Anthropic");

        // Connectors: one per name, whoever provides it; the user's, the extension's, the account's.
        JsonNode slack = item(view, "CONNECTOR", "slack");
        assertThat(slack.path("app").asString()).isEqualTo("claude-desktop");
        assertThat(slack.path("details").path("Fornito da").asString()).isEqualTo("plugin render, plugin sales");
        assertThat(view.path("skills").findValues("name").stream().filter(n -> n.asString().equals("slack")).count()).isEqualTo(1);
        JsonNode extension = item(view, "CONNECTOR", "Blender");
        assertThat(extension.path("app").asString()).isEqualTo("claude-desktop");
        assertThat(extension.path("enabled").asBoolean()).isFalse();
        assertThat(extension.path("details").path("Versione").asString()).isEqualTo("1.0.1");
        JsonNode blender = connector(view, "claude-code", "blender");
        assertThat(blender.path("scope").asString()).isEqualTo("PERSONAL");
        assertThat(blender.path("details").path("Avvio").asString()).isEqualTo("uvx.EXE");
        assertThat(connector(view, "claude-code", "remote").path("details").path("Indirizzo").asString())
                .isEqualTo("https://mcp.example.org/mcp");
        assertThat(connector(view, "claude-code", "Claude Docs").path("scope").asString()).isEqualTo("ACCOUNT");
        // Knowledge: what Claude Code remembers about a project.
        JsonNode memory = item(view, "KNOWLEDGE", "Memoria · Shop");
        assertThat(memory.path("details").path("Progetto").asString()).isEqualTo("C:/Work/Shop");
        assertThat(memory.path("details").path("Ricordi").asString()).isEqualTo("1");
        assertThat(view.path("summary").path("plugins").asInt()).isGreaterThanOrEqualTo(4);

        // Never a secret: not in the list, not in a connector's detail, which never shows the configuration file.
        String everything = view.toString() + getJson("/api/skill-hub/installed/" + blender.path("id").asString())
                + getJson("/api/skill-hub/installed/" + connector(view, "claude-code", "remote").path("id").asString());
        assertThat(everything).doesNotContain("SECRET-ARG-123", "SECRET-ENV-456", "SECRET-QUERY-789", "user:pw",
                "someone@example.org", "BLENDER_API_KEY");
        mockMvc.perform(post("/api/skill-hub/installed/" + blender.path("id").asString() + "/import")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-location-read-only"));
        mockMvc.perform(post("/api/skill-hub/installed/" + blender.path("id").asString() + "/open"))
                .andExpect(status().isConflict());

        JsonNode agentsMd = skill(view, "AGENTS.md");
        assertThat(agentsMd.path("kind").asString()).isEqualTo("KNOWLEDGE");
        assertThat(agentsMd.path("scope").asString()).isEqualTo("INSTRUCTIONS");
        assertThat(agentsMd.path("description").asString()).isEqualTo("Rispondi in italiano.");

        // The library is one of the applications: its catalog skills are there too.
        assertThat(skill(view, "Clean code Java / Spring").path("app").asString()).isEqualTo("aicos");
        assertThat(view.path("notes").toString()).contains("altro account");
        assertThat(view.path("categories").toString()).contains("Documenti e Office");
    }

    @Test
    void aSynchronisationSaysWhatWasInstalledRemovedOrChangedSinceTheLastOne() throws Exception {
        write(home.resolve(".claude/skills/keep/SKILL.md"), "---\nname: keep\ndescription: Stays\n---\n");
        write(home.resolve(".claude/skills/change/SKILL.md"), "---\nname: change\ndescription: Before\n---\n");
        write(home.resolve(".claude/skills/leave/SKILL.md"), "---\nname: leave\ndescription: Goes\n---\n");

        // The first look takes the snapshot: nothing to compare with yet.
        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstRun").value(true))
                .andExpect(jsonPath("$.events.length()").value(0));

        write(home.resolve(".claude/skills/arrive/SKILL.md"), "---\nname: arrive\ndescription: New\n---\n");
        write(home.resolve(".claude/skills/change/SKILL.md"), "---\nname: change\ndescription: After, and longer\n---\n");
        deleteTree(home.resolve(".claude/skills/leave"));

        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstRun").value(false))
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.removed").value(1))
                .andExpect(jsonPath("$.modified").value(1));
        JsonNode events = getJson("/api/skill-hub/events");
        List<String> seen = new ArrayList<>();
        events.forEach(e -> seen.add(e.path("change").asString() + " " + e.path("name").asString() + " " + e.path("app").asString()));
        assertThat(seen).containsExactlyInAnyOrder("ADDED arrive claude-code", "MODIFIED change claude-code",
                "REMOVED leave claude-code");

        // A plugin updated by its application is a change of the plugin, with its versions.
        Path plugin = home.resolve(".claude/plugins/cache/mkt/tools/1.0.0");
        write(plugin.resolve(".claude-plugin/plugin.json"), "{\"name\": \"tools\", \"version\": \"1.0.0\"}");
        write(home.resolve(".claude/plugins/installed_plugins.json"), "{\"plugins\": {\"tools@mkt\": [{\"installPath\": "
                + json.writeValueAsString(plugin.toString()) + ", \"version\": \"1.0.0\"}]}}");
        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(1));
        write(plugin.resolve(".claude-plugin/plugin.json"), "{\"name\": \"tools\", \"version\": \"1.1.0\"}");
        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.modified").value(1))
                .andExpect(jsonPath("$.events[0].detail").value("Plugin tools: 1.0.0 → 1.1.0"));

        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0));
    }

    @Test
    void onAMachineWithNoSkillAtAllTheFirstInstallAfterTheFirstLookIsReported() throws Exception {
        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstRun").value(true))
                .andExpect(jsonPath("$.total").value(0));

        write(home.resolve(".claude/skills/first/SKILL.md"), "---\nname: first\ndescription: The very first\n---\n");
        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstRun").value(false))
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.removed").value(0));
    }

    /**
     * What a newer Hub learns to read -- Claude Desktop from the Microsoft Store,
     * plugins, connectors -- was installed before, not today: a location read for
     * the first time is photographed, not reported as hundreds of installs.
     */
    @Test
    void whatTheHubLearnsToReadIsPhotographedNotReportedAsInstalledToday() throws Exception {
        // The snapshot an earlier version left: the global marker, and one skill since removed.
        jdbc.update("INSERT INTO skill_snapshot (id, app, location, relative_path, name, fingerprint, first_seen_at, last_changed_at) "
                + "VALUES ('baseline', 'aicos', 'baseline', '', 'baseline', '', now(), now()), "
                + "('gone1', 'claude-code', 'claude-code.personal', 'gone/SKILL.md', 'gone', 'x', now(), now())");
        write(home.resolve(".claude/skills/kept/SKILL.md"), "---\nname: kept\ndescription: Was there\n---\n");
        Path account = desktop.resolve("skills-plugin/org1/acct1");
        write(account.resolve("manifest.json"), "{\"lastUpdated\": 2, \"skills\": [{\"skillId\": \"pdf\", \"creatorType\": \"anthropic\"}]}");
        write(account.resolve("skills/pdf/SKILL.md"), "---\nname: pdf\ndescription: Fill PDF forms\n---\n");

        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.firstRun").value(false))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.removed").value(1));

        write(account.resolve("manifest.json"), "{\"lastUpdated\": 3, \"skills\": [{\"skillId\": \"pdf\", \"creatorType\": \"anthropic\"},"
                + " {\"skillId\": \"xlsx\", \"creatorType\": \"anthropic\"}]}");
        write(account.resolve("skills/xlsx/SKILL.md"), "---\nname: xlsx\ndescription: Spreadsheets\n---\n");
        mockMvc.perform(post("/api/skill-hub/sync")).andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.events[0].name").value("xlsx"));
    }

    // --- into the library -----------------------------------------------------------------

    @Test
    void aSkillFoundInAnApplicationIsImportedIntoTheLibraryAndItsSourceChangingIsAnUpdate() throws Exception {
        Path source = home.resolve(".claude/skills/commit-helper");
        write(source.resolve("SKILL.md"), "---\nname: commit-helper\ndescription: Commit messages\n---\n# Version one\n");
        write(source.resolve("examples/good.txt"), "fix: why, not what\n");
        String id = FoundSkill.idOf("claude-code.personal", "commit-helper/SKILL.md");
        scanner.scan();

        mockMvc.perform(post("/api/skill-hub/installed/" + id + "/import").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("commit-helper"))
                .andExpect(jsonPath("$.origin").value("FILE"))
                .andExpect(jsonPath("$.filePath").value("skills/commit-helper/SKILL.md"));
        assertThat(library.root().resolve("skills/commit-helper/examples/good.txt")).exists();
        JsonNode view = getJson("/api/skill-hub/installed");
        JsonNode inLibrary = libraryEntry(view, "commit-helper");
        assertThat(inLibrary.path("provenance").path("provider").asString()).isEqualTo("app");
        assertThat(inLibrary.path("provenance").path("providerName").asString()).isEqualTo("App: Claude Code");
        assertThat(skillById(view, id).path("libraryKey").asString()).isEqualTo("commit-helper");

        mockMvc.perform(post("/api/skill-hub/installed/" + id + "/import").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        write(source.resolve("SKILL.md"), "---\nname: commit-helper\ndescription: Commit messages\n---\n# Version two, longer\n");
        JsonNode check = postJson("/api/skill-hub/updates/check", null);
        assertThat(ids(check)).contains("src:commit-helper");
        apply(List.of("src:commit-helper"), false).andExpect(jsonPath("$.results[0].outcome").value("UPDATED"));
        assertThat(Files.readString(library.root().resolve("skills/commit-helper/SKILL.md"))).contains("Version two");
        assertThat(ids(getJson("/api/skill-hub/updates"))).doesNotContain("src:commit-helper");
    }

    @Test
    void aMarketplaceSkillIsInstalledVerbatimIntoTheLibraryAndCopiedIntoTheApplicationsChosen() throws Exception {
        scriptAnthropicPdf("a1", PDF_V1);
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(home.resolve(".codex"));

        mockMvc.perform(post("/api/skill-hub/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"anthropic-skills\",\"ref\":\"anthropics/skills:skills/pdf\","
                                + "\"targets\":[\"claude-code\",\"agents\",\"cursor\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("pdf"))
                .andExpect(jsonPath("$.copies[0].outcome").value("CREATED"))
                .andExpect(jsonPath("$.copies[1].outcome").value("CREATED"))
                .andExpect(jsonPath("$.copies[2].outcome").value("FAILED"));

        assertThat(Files.readString(library.root().resolve("skills/pdf/SKILL.md"))).isEqualTo(PDF_V1);
        assertThat(library.root().resolve("skills/pdf/scripts/fill.py")).exists();
        assertThat(library.root().resolve("skills/pdf/.DS_Store")).doesNotExist();
        assertThat(Files.readString(home.resolve(".claude/skills/pdf/SKILL.md"))).isEqualTo(PDF_V1);
        assertThat(home.resolve(".claude/skills/pdf/" + Fingerprints.MARKER)).exists();
        assertThat(home.resolve(".agents/skills/pdf/scripts/fill.py")).exists();
        assertThat(copyStatus("pdf", "claude-code")).isEqualTo("IN_SYNC");
        assertThat(copyStatus("pdf", "agents")).isEqualTo("IN_SYNC");

        JsonNode entry = libraryEntry(getJson("/api/skill-hub/installed?rescan=true"), "pdf");
        assertThat(entry.path("provenance").path("provider").asString()).isEqualTo("anthropic-skills");
        assertThat(entry.path("copies").size()).isEqualTo(2);

        mockMvc.perform(post("/api/skill-hub/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"anthropic-skills\",\"ref\":\"anthropics/skills:skills/pdf\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-hub-conflict"));
    }

    @Test
    void aPackageWithAPathThatLeavesItsFolderIsRefusedAndNothingIsWritten() throws Exception {
        web.answer(TREE, "{\"tree\": ["
                + "{\"path\": \"skills/evil/SKILL.md\", \"mode\": \"100644\", \"type\": \"blob\", \"sha\": \"e1\", \"size\": 10},"
                + "{\"path\": \"skills/evil/sub/../../../outside.md\", \"mode\": \"100644\", \"type\": \"blob\", \"sha\": \"e2\", \"size\": 10}]}");
        web.answer(RAW + "skills/evil/SKILL.md", "---\nname: evil\ndescription: x\n---\n");

        mockMvc.perform(post("/api/skill-hub/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"anthropic-skills\",\"ref\":\"anthropics/skills:skills/evil\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-package-refused"));
        assertThat(library.root().resolve("skills/evil")).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM harness_resources WHERE key = 'evil'", Integer.class)).isZero();
    }

    // --- copies in step -------------------------------------------------------------------

    @Test
    void copiesStayInStepPushedWhenTheLibraryChangesPulledWhenTheApplicationDoes() throws Exception {
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(home.resolve(".verdent"));
        createLibrarySkill();
        deploy("api-review", "claude-code", false).andExpect(jsonPath("$.outcome").value("CREATED"));
        deploy("api-review", "verdent", false).andExpect(jsonPath("$.outcome").value("CREATED"));
        String claudeCopy = Files.readString(home.resolve(".claude/skills/api-review/SKILL.md"));
        // The applications read an identifier as the name, not the library's title.
        assertThat(Frontmatter.read(claudeCopy)).containsEntry("name", "api-review")
                .containsEntry("metadata.title", "Revisione delle API REST").doesNotContainKey("key");
        deploy("api-review", "claude-code", false).andExpect(jsonPath("$.outcome").value("UNCHANGED"));

        // The library moves on: both copies are behind, and catch up.
        saveLibraryFile("api-review", "- Mai 200 per una creazione.", "- Mai 200 per una creazione: 201 con Location.");
        String claudeId = FoundSkill.idOf("claude-code.personal", "api-review/SKILL.md");
        String verdentId = FoundSkill.idOf("verdent.personal", "api-review/SKILL.md");
        scanner.scan();
        assertThat(ids(getJson("/api/skill-hub/updates"))).contains("push:" + claudeId, "push:" + verdentId);
        apply(List.of("push:" + claudeId, "push:" + verdentId), false)
                .andExpect(jsonPath("$.results[0].outcome").value("UPDATED"))
                .andExpect(jsonPath("$.results[1].outcome").value("UPDATED"));
        assertThat(Files.readString(home.resolve(".verdent/skills/api-review/SKILL.md"))).contains("201 con Location");
        assertThat(copyStatus("api-review", "claude-code")).isEqualTo("IN_SYNC");

        // Somebody improves the skill inside Claude Code: it comes back to the library, and Verdent follows.
        Path inClaude = home.resolve(".claude/skills/api-review/SKILL.md");
        write(inClaude, Files.readString(inClaude) + "- Un 404 è un problem detail anche lui.\n");
        scanner.scan();
        assertThat(copyStatus("api-review", "claude-code")).isEqualTo("MODIFIED");
        apply(List.of("pull:" + claudeId), false).andExpect(jsonPath("$.results[0].outcome").value("UPDATED"));
        String libraryNow = Files.readString(library.root().resolve("skills/api-review/SKILL.md"));
        assertThat(libraryNow).contains("Un 404 è un problem detail").contains("key: api-review").contains("kind: SKILL");
        assertThat(Files.readString(home.resolve(".verdent/skills/api-review/SKILL.md"))).contains("Un 404 è un problem detail");
        assertThat(copyStatus("api-review", "claude-code")).isEqualTo("IN_SYNC");
        assertThat(copyStatus("api-review", "verdent")).isEqualTo("IN_SYNC");

        // Both sides change: nothing is chosen silently; a confirmed choice keeps a backup.
        saveLibraryFile("api-review", "- Un 404 è un problem detail anche lui.", "- Un 404 è un problem detail (sempre).");
        write(inClaude, Files.readString(inClaude) + "- E un 409 dice cosa fare.\n");
        scanner.scan();
        assertThat(copyStatus("api-review", "claude-code")).isEqualTo("CONFLICT");
        apply(List.of("pull:" + claudeId), false).andExpect(jsonPath("$.results[0].outcome").value("CONFLICT"));
        apply(List.of("pull:" + claudeId), true).andExpect(jsonPath("$.results[0].outcome").value("UPDATED"));
        assertThat(Files.readString(library.root().resolve("skills/api-review/SKILL.md"))).contains("E un 409 dice cosa fare");
        assertThat(backups()).anyMatch(name -> name.startsWith("libreria-api-review"));
    }

    @Test
    void theHubNeverOverwritesWhatItDidNotWriteAndARemovalIsABackup() throws Exception {
        Files.createDirectories(home.resolve(".claude"));
        createLibrarySkill();
        write(home.resolve(".claude/skills/api-review/SKILL.md"), "---\nname: api-review\ndescription: Mine, by hand\n---\n");

        deploy("api-review", "claude-code", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-hub-conflict"));
        assertThat(Files.readString(home.resolve(".claude/skills/api-review/SKILL.md"))).contains("Mine, by hand");

        deploy("api-review", "claude-code", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.backup").isNotEmpty());
        assertThat(Files.readString(home.resolve(".claude/skills/api-review/SKILL.md"))).doesNotContain("Mine, by hand");
        Path backup = Path.of(aicosHome).resolve("backups/skills");
        assertThat(backups()).anyMatch(name -> name.startsWith("claude-code-api-review"));
        try (Stream<Path> walk = Files.walk(backup)) {
            assertThat(walk.filter(p -> p.getFileName().toString().equals("SKILL.md")).anyMatch(p -> read(p).contains("Mine, by hand")))
                    .isTrue();
        }

        // Claude Desktop's folder belongs to the account: it gets a package to upload instead.
        deploy("api-review", "claude-desktop", false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-location-read-only"));

        // Taking a copy out is an admin's act, and it is a backup, not a deletion.
        mockMvc.perform(delete("/api/skill-hub/library/api-review/copies/claude-code")).andExpect(status().isForbidden());
        String admin = AdminSession.bearer(mockMvc);
        mockMvc.perform(delete("/api/skill-hub/library/api-review/copies/claude-code").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REMOVED"));
        assertThat(home.resolve(".claude/skills/api-review")).doesNotExist();
        assertThat(backups().stream().filter(name -> name.startsWith("claude-code-api-review")).count()).isEqualTo(2);

        // A folder the Hub did not write is never taken out from here.
        write(home.resolve(".claude/skills/api-review/SKILL.md"), "---\nname: api-review\ndescription: Again by hand\n---\n");
        mockMvc.perform(delete("/api/skill-hub/library/api-review/copies/claude-code").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
        assertThat(home.resolve(".claude/skills/api-review/SKILL.md")).exists();
    }

    @Test
    void aLibrarySkillIsPackagedAsAZipForClaudeDesktop() throws Exception {
        createLibrarySkill();
        MvcResult zip = mockMvc.perform(get("/api/skill-hub/library/api-review/package.zip")).andExpect(status().isOk()).andReturn();
        assertThat(zip.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("api-review.zip");
        List<String> entries = new ArrayList<>();
        String skillMd = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.add(e.getName());
                if (e.getName().equals("api-review/SKILL.md")) {
                    skillMd = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(entries).containsExactly("api-review/SKILL.md");
        assertThat(Frontmatter.read(skillMd)).containsEntry("name", "api-review");
    }

    // --- marketplaces and updates -----------------------------------------------------------

    @Test
    void aSearchAsksEveryMarketplaceAndOneThatIsDownHidesNothing() throws Exception {
        web.answer("https://skills.sh/api/search?q=pdf&limit=30", "{\"skills\": ["
                + "{\"id\": \"anthropics/skills/pdf\", \"source\": \"anthropics/skills\", \"skillId\": \"pdf\", \"name\": \"pdf\", \"installs\": 206133},"
                + "{\"id\": \"../../etc\", \"source\": \"x\", \"skillId\": \"x\", \"name\": \"bad\"}]}");
        web.down("clawhub.ai");
        scriptAnthropicPdf("a1", PDF_V1);

        JsonNode found = getJson("/api/skill-hub/marketplaces/search?q=pdf");
        List<String> refs = new ArrayList<>();
        found.path("results").forEach(r -> refs.add(r.path("provider").asString() + " " + r.path("ref").asString()));
        assertThat(refs).contains("skills-sh anthropics/skills/pdf", "anthropic-skills anthropics/skills:skills/pdf")
                .noneMatch(r -> r.contains("etc"));
        List<String> failing = new ArrayList<>();
        found.path("errors").forEach(e -> failing.add(e.path("provider").asString()));
        assertThat(failing).contains("clawhub");

        // A skills.sh id is resolved to its folder; the preview says what installing brings.
        JsonNode preview = getJson("/api/skill-hub/marketplaces/skills-sh/package?ref=anthropics/skills/pdf");
        assertThat(preview.path("skillMd").asString()).isEqualTo(PDF_V1);
        assertThat(preview.path("files").toString()).contains("scripts/fill.py").doesNotContain(".DS_Store");
        assertThat(preview.path("warnings").toString()).contains("script");
    }

    @Test
    void anUpstreamUpdateIsFoundAndAppliedAndALocalEditIsNeverLostSilently() throws Exception {
        scriptAnthropicPdf("a1", PDF_V1);
        Files.createDirectories(home.resolve(".claude"));
        mockMvc.perform(post("/api/skill-hub/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"anthropic-skills\",\"ref\":\"anthropics/skills:skills/pdf\",\"targets\":[\"claude-code\"]}"))
                .andExpect(status().isCreated());
        assertThat(ids(postJson("/api/skill-hub/updates/check", null))).doesNotContain("mkt:pdf");

        scriptAnthropicPdf("a2", PDF_V2);
        assertThat(ids(postJson("/api/skill-hub/updates/check", null))).contains("mkt:pdf");
        apply(List.of("mkt:pdf"), false).andExpect(jsonPath("$.results[0].outcome").value("UPDATED"));
        assertThat(Files.readString(library.root().resolve("skills/pdf/SKILL.md"))).isEqualTo(PDF_V2);
        // The untouched copy followed.
        assertThat(Files.readString(home.resolve(".claude/skills/pdf/SKILL.md"))).isEqualTo(PDF_V2);

        // The operator edits the library; upstream moves again: the update waits for a decision.
        Path md = library.root().resolve("skills/pdf/SKILL.md");
        write(md, Files.readString(md) + "\n- La mia regola.\n");
        scriptAnthropicPdf("a3", PDF_V3);
        postJson("/api/skill-hub/updates/check", null);
        apply(List.of("mkt:pdf"), false).andExpect(jsonPath("$.results[0].outcome").value("CONFLICT"));
        assertThat(Files.readString(md)).contains("La mia regola");
        apply(List.of("mkt:pdf"), true).andExpect(jsonPath("$.results[0].outcome").value("UPDATED"));
        assertThat(Files.readString(md)).isEqualTo(PDF_V3);
        assertThat(backups()).anyMatch(name -> name.startsWith("libreria-pdf"));
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static final String TREE = "https://api.github.com/repos/anthropics/skills/git/trees/HEAD?recursive=1";
    private static final String RAW = "https://raw.githubusercontent.com/anthropics/skills/HEAD/";
    private static final String PDF_V1 = "---\nname: pdf\ndescription: Fill and merge PDF files.\n---\n\n# PDF\n\nUse pypdf.\n";
    private static final String PDF_V2 = PDF_V1 + "\n## Forms\n\nRead forms.md first.\n";
    private static final String PDF_V3 = PDF_V2 + "\n## OCR\n\nUse tesseract.\n";

    private void scriptAnthropicPdf(String sha, String skillMd) {
        markets.clearCaches();
        web.answer(TREE, "{\"sha\": \"t\", \"truncated\": false, \"tree\": ["
                + "{\"path\": \"skills\", \"mode\": \"040000\", \"type\": \"tree\", \"sha\": \"d0\"},"
                + "{\"path\": \"skills/pdf/SKILL.md\", \"mode\": \"100644\", \"type\": \"blob\", \"sha\": \"" + sha + "\", \"size\": "
                + skillMd.length() + "},"
                + "{\"path\": \"skills/pdf/scripts/fill.py\", \"mode\": \"100644\", \"type\": \"blob\", \"sha\": \"b1\", \"size\": 20},"
                + "{\"path\": \"skills/pdf/.DS_Store\", \"mode\": \"100644\", \"type\": \"blob\", \"sha\": \"c1\", \"size\": 3},"
                + "{\"path\": \"skills/pdf/link\", \"mode\": \"120000\", \"type\": \"blob\", \"sha\": \"l1\", \"size\": 9},"
                + "{\"path\": \"skills/xlsx/SKILL.md\", \"mode\": \"100644\", \"type\": \"blob\", \"sha\": \"x1\", \"size\": 40}]}");
        web.answer(RAW + "skills/pdf/SKILL.md", skillMd);
        web.answer(RAW + "skills/pdf/scripts/fill.py", "print('fill the form')\n");
        web.answer(RAW + "skills/xlsx/SKILL.md", "---\nname: xlsx\ndescription: Spreadsheets\n---\n");
    }

    private static final String LIBRARY_SKILL = """
            ---
            key: api-review
            name: Revisione delle API REST
            kind: SKILL
            description: Controlla verbi, codici di stato e problem detail.
            tags: [api, rest]
            ---

            # Revisione delle API REST

            - Mai 200 per una creazione.
            """;

    private void createLibrarySkill() throws Exception {
        mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("content", LIBRARY_SKILL))))
                .andExpect(status().isCreated());
    }

    private void saveLibraryFile(String key, String from, String to) throws Exception {
        JsonNode file = getJson("/api/resources/" + key + "/file");
        String content = file.path("content").asString();
        assertThat(content).contains(from);
        mockMvc.perform(put("/api/resources/" + key + "/file").header(HttpHeaders.IF_MATCH, "\"" + file.path("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("content", content.replace(from, to)))))
                .andExpect(status().isOk());
    }

    private ResultActions deploy(String key, String app, boolean replace) throws Exception {
        return mockMvc.perform(post("/api/skill-hub/library/" + key + "/copies/" + app).contentType(MediaType.APPLICATION_JSON)
                .content("{\"replace\": " + replace + "}"));
    }

    private ResultActions apply(List<String> ids, boolean overwrite) throws Exception {
        return mockMvc.perform(post("/api/skill-hub/updates/apply").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("ids", ids, "overwrite", overwrite)))).andExpect(status().isOk());
    }

    private String copyStatus(String key, String app) throws Exception {
        for (JsonNode copy : getJson("/api/skill-hub/library/" + key + "/copies")) {
            if (copy.path("app").asString().equals(app)) {
                return copy.path("status").asString();
            }
        }
        throw new AssertionError("No copy target " + app);
    }

    private List<String> backups() throws IOException {
        Path folder = Path.of(aicosHome).resolve("backups/skills");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(p -> p.getFileName().toString()).toList();
        }
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> ids(JsonNode updates) {
        List<String> out = new ArrayList<>();
        updates.path("items").forEach(i -> out.add(i.path("id").asString()));
        return out;
    }

    private static JsonNode app(JsonNode view, String id) {
        for (JsonNode app : view.path("apps")) {
            if (app.path("id").asString().equals(id)) {
                return app;
            }
        }
        throw new AssertionError("No app " + id);
    }

    private static JsonNode skill(JsonNode view, String name) {
        for (JsonNode skill : view.path("skills")) {
            if (skill.path("name").asString().equals(name)) {
                return skill;
            }
        }
        throw new AssertionError("No skill " + name + " in " + names(view));
    }

    private static JsonNode item(JsonNode view, String kind, String name) {
        for (JsonNode item : view.path("skills")) {
            if (item.path("kind").asString().equals(kind) && item.path("name").asString().equals(name)) {
                return item;
            }
        }
        throw new AssertionError("No " + kind + " " + name + " in " + names(view));
    }

    private static JsonNode connector(JsonNode view, String app, String name) {
        for (JsonNode item : view.path("skills")) {
            if (item.path("kind").asString().equals("CONNECTOR") && item.path("app").asString().equals(app)
                    && item.path("name").asString().equals(name)) {
                return item;
            }
        }
        throw new AssertionError("No connector " + name + " of " + app);
    }

    private static JsonNode skillById(JsonNode view, String id) {
        for (JsonNode skill : view.path("skills")) {
            if (skill.path("id").asString().equals(id)) {
                return skill;
            }
        }
        throw new AssertionError("No skill " + id);
    }

    private static JsonNode libraryEntry(JsonNode view, String key) {
        for (JsonNode skill : view.path("skills")) {
            if (skill.path("app").asString().equals("aicos") && skill.path("libraryKey").asString().equals(key)) {
                return skill;
            }
        }
        throw new AssertionError("No library entry " + key);
    }

    private static List<String> names(JsonNode view) {
        List<String> out = new ArrayList<>();
        view.path("skills").forEach(s -> out.add(s.path("name").asString()));
        return out;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
