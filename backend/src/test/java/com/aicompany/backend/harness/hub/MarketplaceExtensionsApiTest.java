package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.hub.HubCommands.Tool;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHubCommands;
import com.aicompany.backend.support.FakeHubWeb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 31 (ADR-036 §6b): plugins and connectors in the Marketplace -- their
 * catalogs searched by kind, what each application would get, and installing
 * them through the applications' own CLIs (a {@link FakeHubCommands}: nothing
 * runs) or Claude Desktop's configuration file, never showing back a key.
 */
class MarketplaceExtensionsApiTest extends AbstractPostgresTest {

    private static final String CLAUDE_CATALOG =
            "https://raw.githubusercontent.com/anthropics/claude-plugins-official/HEAD/.claude-plugin/marketplace.json";
    private static final String CODEX_CATALOG = "https://raw.githubusercontent.com/openai/plugins/HEAD/.agents/plugins/marketplace.json";
    private static final String REGISTRY = "https://registry.modelcontextprotocol.io/v0/servers?limit=40&version=latest&search=";
    private static final String SHA = "a".repeat(64);
    private static final String SECRET = "sk-live-SECRET-0042";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillLocations locations;

    @Autowired
    private SkillMarketplaces markets;

    @Autowired
    private SkillScanner scanner;

    @Autowired
    private FakeHubWeb web;

    @Autowired
    private FakeHubCommands clis;

    @Value("${aicos.home}")
    private String aicosHome;

    @Value("${aicos.hub.local-app-data}")
    private String localAppData;

    private final JsonMapper json = JsonMapper.builder().build();

    private Path home;
    private Path desktopHome;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void aCleanMachine() throws IOException {
        jdbc.update("DELETE FROM hub_connector_installs");
        web.reset();
        clis.reset();
        markets.clearCaches();
        home = locations.home();
        desktopHome = Path.of(localAppData).resolve("Packages/Claude_test1234/LocalCache/Roaming/Claude");
        for (Path folder : List.of(home, locations.claudeDesktopCandidates().getFirst().getParent(), Path.of(localAppData),
                Path.of(aicosHome).resolve("backups"))) {
            deleteTree(folder);
        }
        Files.createDirectories(home);
        scanner.scan();
    }

    @Test
    void pluginAndConnectorCatalogsAreSearchedByKindAndSayWhereEachInstalls() throws Exception {
        scriptCatalogs();

        JsonNode plugins = getJson("/api/skill-hub/marketplaces/search?q=design&kind=PLUGIN");
        JsonNode frontend = result(plugins, "claude-plugins-official", "frontend-design");
        assertThat(frontend.path("kind").asString()).isEqualTo("PLUGIN");
        assertThat(frontend.path("targets").toString()).isEqualTo("[\"claude-code\"]");
        assertThat(frontend.path("details").path("Categoria").asString()).isEqualTo("development");
        assertThat(frontend.path("url").asString()).isEqualTo("https://github.com/anthropics/claude-plugins-official/tree/HEAD/plugins/frontend-design");
        assertThat(plugins.toString()).doesNotContain("evil");
        // A search among plugins asks no skill or connector catalog.
        assertThat(web.asked()).noneMatch(u -> u.getHost().equals("skills.sh") || u.getHost().equals("registry.modelcontextprotocol.io"));

        // Codex's catalog names its plugins; their manifests say what they are.
        JsonNode linear = getJson("/api/skill-hub/marketplaces/openai-plugins/browse").get(0);
        assertThat(linear.path("name").asString()).isEqualTo("Linear");
        assertThat(linear.path("description").asString()).isEqualTo("Issues and projects");
        assertThat(linear.path("targets").toString()).isEqualTo("[\"codex\"]");

        JsonNode connectors = getJson("/api/skill-hub/marketplaces/search?q=acme&kind=CONNECTOR");
        JsonNode files = result(connectors, "mcp-registry", "io.github.acme/files");
        assertThat(files.path("kind").asString()).isEqualTo("CONNECTOR");
        assertThat(files.path("details").path("Richiede").asString()).isEqualTo("ACME_KEY");
        assertThat(files.path("details").path("Pacchetto").asString()).isEqualTo("npm · npx -y @acme/files-mcp@0.3.0");
        assertThat(result(connectors, "mcp-registry", "io.github.acme/notes").path("details").path("Tipo").asString())
                .isEqualTo("remoto (http)");
        JsonNode templated = result(connectors, "mcp-registry", "io.github.acme/templated");
        assertThat(templated.path("targets").size()).isZero();
        assertThat(templated.path("details").path("Tipo").asString()).isEqualTo("non installabile da qui");

        // The preview says how each application would get it -- a local server through cmd /c on Windows --
        // from what the search brought: the registry, slow at times, is not asked again.
        long registryCalls = web.asked().stream().filter(u -> u.getHost().equals("registry.modelcontextprotocol.io")).count();
        JsonNode local = getJson("/api/skill-hub/marketplaces/mcp-registry/item?ref=io.github.acme/files");
        assertThat(web.asked().stream().filter(u -> u.getHost().equals("registry.modelcontextprotocol.io")).count()).isEqualTo(registryCalls);
        assertThat(local.path("installName").asString()).isEqualTo("files");
        assertThat(local.path("inputs").toString()).contains("ACME_KEY", "ACME_REGION");
        assertThat(plan(local, "claude-code").path("command").asString())
                .isEqualTo("claude mcp add --scope user -e ACME_REGION=eu files -- cmd /c npx -y @acme/files-mcp@0.3.0");
        assertThat(plan(local, "codex").path("command").asString())
                .isEqualTo("codex mcp add files --env ACME_REGION=eu -- cmd /c npx -y @acme/files-mcp@0.3.0");
        assertThat(plan(local, "claude-desktop").path("method").asString()).isEqualTo("CONFIG");
        assertThat(local.path("warnings").toString()).contains("programma");
        JsonNode remote = getJson("/api/skill-hub/marketplaces/mcp-registry/item?ref=io.github.acme/notes");
        assertThat(plan(remote, "claude-code").path("command").asString())
                .isEqualTo("claude mcp add --scope user --transport http notes https://mcp.acme.example/mcp");
        assertThat(plan(remote, "codex").path("method").asString()).isEqualTo("CONFIG");   // PHASE 32 (TD-89): config.toml
        assertThat(plan(remote, "claude-desktop").path("note").asString()).contains("connettore personalizzato");
        JsonNode codexPlugin = getJson("/api/skill-hub/marketplaces/openai-plugins/item?ref=linear");
        assertThat(codexPlugin.path("name").asString()).isEqualTo("Linear");
        assertThat(codexPlugin.path("installName").asString()).isEqualTo("linear");
        assertThat(codexPlugin.path("description").asString()).isEqualTo("Issues and projects");
        JsonNode plugin = getJson("/api/skill-hub/marketplaces/claude-plugins-official/item?ref=frontend-design");
        assertThat(plugin.path("details").path("Versione").asString()).isEqualTo("1.0.0");
        assertThat(plan(plugin, "claude-code").path("command").asString())
                .isEqualTo("claude plugin marketplace add anthropics/claude-plugins-official\n"
                        + "claude plugin install frontend-design@claude-plugins-official --scope user");

        // A catalog of plugins is not a skill package.
        mockMvc.perform(get("/api/skill-hub/marketplaces/claude-plugins-official/package?ref=frontend-design"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:skill-package-refused"));
        assertThat(clis.calls()).isEmpty();
    }

    @Test
    void aPluginIsInstalledWithTheApplicationsCliAndADeclaredCommandWaitsForTheOperator() throws Exception {
        scriptCatalogs();
        clis.answer(Tool.CLAUDE, List.of("plugin", "install"), 1,
                "{\"command\":\"install\",\"outcome\":\"failed\",\"plugin\":\"frontend-design\","
                        + "\"shownCommand\":{\"command\":\"npx -y @anthropic/frontend-design-setup\",\"sha256\":\"" + SHA + "\"}}\n");
        clis.answerWith(Tool.CLAUDE, List.of("plugin", "install"), "--accept-command", 0,
                "{\"command\":\"install\",\"outcome\":\"installed\",\"plugin\":\"frontend-design\"}\n");

        JsonNode asked = postJson("/api/skill-hub/plugins/install",
                "{\"provider\":\"claude-plugins-official\",\"ref\":\"frontend-design\",\"target\":\"claude-code\"}");
        assertThat(asked.path("status").asString()).isEqualTo("NEEDS_CONFIRMATION");
        assertThat(asked.path("command").asString()).isEqualTo("npx -y @anthropic/frontend-design-setup");
        assertThat(asked.path("confirmation").asString()).isEqualTo(SHA);
        // The marketplace was not known: it is added first, then the plugin -- each an argument list.
        assertThat(clis.calls()).extracting(FakeHubCommands.Call::arguments).containsExactly(
                List.of("plugin", "marketplace", "add", "anthropics/claude-plugins-official"),
                List.of("plugin", "install", "frontend-design@claude-plugins-official", "--scope", "user", "--json"));

        write(home.resolve(".claude/plugins/known_marketplaces.json"), "{\"claude-plugins-official\": {}}");
        clis.reset();
        clis.answerWith(Tool.CLAUDE, List.of("plugin", "install"), "--accept-command", 0,
                "{\"command\":\"install\",\"outcome\":\"installed\",\"plugin\":\"frontend-design\"}\n");
        JsonNode installed = postJson("/api/skill-hub/plugins/install",
                "{\"provider\":\"claude-plugins-official\",\"ref\":\"frontend-design\",\"target\":\"claude-code\",\"confirmation\":\"" + SHA + "\"}");
        assertThat(installed.path("status").asString()).isEqualTo("INSTALLED");
        assertThat(clis.calls()).extracting(FakeHubCommands.Call::arguments).containsExactly(
                List.of("plugin", "install", "frontend-design@claude-plugins-official", "--scope", "user", "--json", "--accept-command", SHA));

        // Codex: its CLI, its marketplace already configured.
        clis.answer(Tool.CODEX, List.of("plugin", "marketplace", "list"), 0,
                "{\"marketplaces\": [{\"name\": \"openai-curated\"}, {\"name\": \"openai-bundled\"}]}");
        JsonNode codex = postJson("/api/skill-hub/plugins/install",
                "{\"provider\":\"openai-plugins\",\"ref\":\"linear\",\"target\":\"codex\"}");
        assertThat(codex.path("status").asString()).isEqualTo("INSTALLED");
        assertThat(clis.calls().getLast()).isEqualTo(new FakeHubCommands.Call(Tool.CODEX, List.of("plugin", "add", "linear@openai-curated", "--json")));

        // A failure says so, with what the CLI printed.
        clis.answer(Tool.CLAUDE, List.of("plugin", "install"), 1,
                "{\"command\":\"install\",\"outcome\":\"failed\",\"failureCode\":\"not_found\",\"message\":\"Plugin not found\"}\n");
        JsonNode failed = postJson("/api/skill-hub/plugins/install",
                "{\"provider\":\"claude-plugins-official\",\"ref\":\"frontend-design\",\"target\":\"claude-code\"}");
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("message").asString()).isEqualTo("Plugin not found");

        // Claude Desktop's plugins come with the account: the Hub says where.
        JsonNode desktop = postJson("/api/skill-hub/plugins/install",
                "{\"provider\":\"knowledge-work-plugins\",\"ref\":\"sales\",\"target\":\"claude-desktop\"}");
        assertThat(desktop.path("status").asString()).isEqualTo("MANUAL");
        assertThat(desktop.path("message").asString()).contains("Personalizza", "sales");

        // Not a destination of that catalog, a forged confirmation, a name that is not a plugin's: refused before anything runs.
        int before = clis.calls().size();
        mockMvc.perform(post("/api/skill-hub/plugins/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"openai-plugins\",\"ref\":\"linear\",\"target\":\"claude-code\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/skill-hub/plugins/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"claude-plugins-official\",\"ref\":\"frontend-design\",\"target\":\"claude-code\","
                                + "\"confirmation\":\"x; rm -rf /\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/skill-hub/plugins/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"claude-plugins-official\",\"ref\":\"--yes\",\"target\":\"claude-code\"}"))
                .andExpect(status().isNotFound());
        assertThat(clis.calls()).hasSize(before);
    }

    /**
     * PHASE 32 (TD-88): a plugin's options (userConfig) are asked in the dialog and given to Claude Code at
     * install; a required one missing, or one the plugin does not declare, is refused before anything runs;
     * a secret never comes back.
     */
    @Test
    void aPluginsOptionsAreAskedAndGivenToClaudeCodeAtInstall() throws Exception {
        scriptCatalogs();
        web.answer("https://raw.githubusercontent.com/anthropics/claude-plugins-official/HEAD/plugins/frontend-design/.claude-plugin/plugin.json",
                "{\"name\": \"frontend-design\", \"version\": \"1.0.0\", \"userConfig\": {"
                        + "\"api_token\": {\"type\": \"string\", \"title\": \"Token\", \"sensitive\": true, \"required\": true},"
                        + "\"region\": {\"type\": \"string\", \"description\": \"Regione\", \"default\": \"eu\"}}}");
        write(home.resolve(".claude/plugins/known_marketplaces.json"), "{\"claude-plugins-official\": {}}");
        JsonNode preview = getJson("/api/skill-hub/marketplaces/claude-plugins-official/item?ref=frontend-design");
        assertThat(preview.path("inputs").toString())
                .contains("\"name\":\"api_token\"").contains("\"kind\":\"CONFIG\"").contains("\"secret\":true")
                .contains("\"defaultValue\":\"eu\"");

        int before = clis.calls().size();
        mockMvc.perform(post("/api/skill-hub/plugins/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"claude-plugins-official\",\"ref\":\"frontend-design\",\"target\":\"claude-code\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/skill-hub/plugins/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"claude-plugins-official\",\"ref\":\"frontend-design\",\"target\":\"claude-code\","
                                + "\"values\":{\"api_token\":\"tok-123456\",\"other\":\"x\"}}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(clis.calls()).hasSize(before);

        clis.answer(Tool.CLAUDE, List.of("plugin", "install"), 0,
                "{\"command\":\"install\",\"outcome\":\"installed\",\"plugin\":\"frontend-design\",\"echo\":\"tok-123456\"}\n");
        JsonNode installed = postJson("/api/skill-hub/plugins/install",
                "{\"provider\":\"claude-plugins-official\",\"ref\":\"frontend-design\",\"target\":\"claude-code\","
                        + "\"values\":{\"api_token\":\"tok-123456\",\"region\":\"us\"}}");
        assertThat(installed.path("status").asString()).isEqualTo("INSTALLED");
        assertThat(clis.calls().getLast().arguments()).containsSubsequence("--config", "api_token=tok-123456", "--config", "region=us");
        assertThat(installed.toString()).doesNotContain("tok-123456");
    }

    @Test
    void aConnectorIsAddedToEachApplicationChosenAndItsKeyIsNeverShownBack() throws Exception {
        scriptCatalogs();
        write(desktopHome.resolve("claude_desktop_config.json"), "{\"preferences\": {\"menuBarEnabled\": true}, "
                + "\"mcpServers\": {\"blender\": {\"command\": \"uvx\", \"args\": [\"blender-mcp\"]}}}");
        clis.answer(Tool.CLAUDE, List.of("mcp", "add"), 0, "Added stdio MCP server files with command: cmd /c npx -y @acme/files-mcp@0.3.0 "
                + "and env ACME_KEY=" + SECRET + "\n");

        JsonNode response = postJson("/api/skill-hub/connectors/install", json.writeValueAsString(Map.of(
                "provider", "mcp-registry", "ref", "io.github.acme/files",
                "targets", List.of("claude-code", "codex", "claude-desktop", "cursor"),
                "values", Map.of("ACME_KEY", SECRET))));

        assertThat(response.toString()).doesNotContain(SECRET);
        assertThat(outcome(response, "claude-code").path("status").asString()).isEqualTo("INSTALLED");
        assertThat(outcome(response, "claude-code").path("output").asString()).contains("ACME_KEY=••••");
        assertThat(outcome(response, "codex").path("status").asString()).isEqualTo("INSTALLED");
        assertThat(outcome(response, "claude-desktop").path("status").asString()).isEqualTo("INSTALLED");
        assertThat(outcome(response, "cursor").path("status").asString()).isEqualTo("FAILED");
        // The applications get the key; their arguments are lists, never a shell line.
        assertThat(clis.calls()).containsExactly(
                new FakeHubCommands.Call(Tool.CLAUDE, List.of("mcp", "add", "--scope", "user", "-e", "ACME_KEY=" + SECRET,
                        "-e", "ACME_REGION=eu", "files", "--", "cmd", "/c", "npx", "-y", "@acme/files-mcp@0.3.0")),
                new FakeHubCommands.Call(Tool.CODEX, List.of("mcp", "add", "files", "--env", "ACME_KEY=" + SECRET,
                        "--env", "ACME_REGION=eu", "--", "cmd", "/c", "npx", "-y", "@acme/files-mcp@0.3.0")));

        // Claude Desktop's file keeps everything it had, and a backup of it is kept.
        JsonNode config = json.readTree(Files.readString(desktopHome.resolve("claude_desktop_config.json")));
        assertThat(config.path("preferences").path("menuBarEnabled").asBoolean()).isTrue();
        assertThat(config.path("mcpServers").path("blender").path("command").asString()).isEqualTo("uvx");
        JsonNode files = config.path("mcpServers").path("files");
        assertThat(files.path("command").asString()).isEqualTo("cmd");
        assertThat(files.path("args").toString()).isEqualTo("[\"/c\",\"npx\",\"-y\",\"@acme/files-mcp@0.3.0\"]");
        assertThat(files.path("env").path("ACME_KEY").asString()).isEqualTo(SECRET);
        try (Stream<Path> backups = Files.list(Path.of(aicosHome).resolve("backups/app-config"))) {
            Path backup = backups.findFirst().orElseThrow();
            assertThat(Files.readString(backup)).contains("blender").doesNotContain("@acme/files-mcp");
        }

        // The Hub sees it at once, and never shows the key from the file.
        JsonNode installed = getJson("/api/skill-hub/installed");
        assertThat(installed.toString()).contains("\"name\":\"files\"").doesNotContain(SECRET);

        // A second time, Claude Desktop already has one by that name: refused unless replacing.
        JsonNode again = postJson("/api/skill-hub/connectors/install", json.writeValueAsString(Map.of(
                "provider", "mcp-registry", "ref", "io.github.acme/files", "targets", List.of("claude-desktop"),
                "values", Map.of("ACME_KEY", SECRET))));
        assertThat(outcome(again, "claude-desktop").path("status").asString()).isEqualTo("FAILED");
        assertThat(outcome(again, "claude-desktop").path("message").asString()).contains("già");

        // A remote one: the key travels in a header for Claude Code, into Codex's config.toml (its CLI takes no
        // header -- PHASE 32, TD-89); Claude Desktop is told where to add it.
        clis.reset();
        JsonNode remote = postJson("/api/skill-hub/connectors/install", json.writeValueAsString(Map.of(
                "provider", "mcp-registry", "ref", "io.github.acme/notes", "name", "acme-notes",
                "targets", List.of("claude-code", "codex", "claude-desktop"),
                "values", Map.of("Authorization", "Bearer " + SECRET))));
        assertThat(remote.toString()).doesNotContain(SECRET);
        assertThat(outcome(remote, "claude-code").path("command").asString())
                .isEqualTo("claude mcp add --scope user --transport http acme-notes https://mcp.acme.example/mcp --header \"Authorization: ••••\"");
        assertThat(outcome(remote, "codex").path("status").asString()).isEqualTo("INSTALLED");
        assertThat(Files.readString(home.resolve(".codex/config.toml"))).contains("[mcp_servers.acme-notes]")
                .contains("url = \"https://mcp.acme.example/mcp\"")
                .contains("http_headers = { \"Authorization\" = \"Bearer " + SECRET + "\" }");
        assertThat(outcome(remote, "claude-desktop").path("status").asString()).isEqualTo("MANUAL");
        assertThat(clis.calls()).containsExactly(new FakeHubCommands.Call(Tool.CLAUDE, List.of("mcp", "add", "--scope", "user",
                "--transport", "http", "acme-notes", "https://mcp.acme.example/mcp", "--header", "Authorization: Bearer " + SECRET)));

        // What is missing or malformed is refused before anything runs.
        clis.reset();
        mockMvc.perform(post("/api/skill-hub/connectors/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"mcp-registry\",\"ref\":\"io.github.acme/files\",\"targets\":[\"claude-code\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Serve un valore per ACME_KEY"));
        mockMvc.perform(post("/api/skill-hub/connectors/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"mcp-registry\",\"ref\":\"io.github.acme/files\",\"targets\":[\"claude-code\"],"
                                + "\"name\":\"--evil\",\"values\":{\"ACME_KEY\":\"k-123\"}}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/skill-hub/connectors/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"mcp-registry\",\"ref\":\"io.github.acme/templated\",\"targets\":[\"claude-code\"]}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(clis.calls()).isEmpty();
    }

    /**
     * PHASE 32 (TD-89): a remote address with parts its remote declares, and a package that wants command-line
     * arguments, are installed with what the operator gives; a flag the Hub cannot pass stays manual.
     */
    @Test
    void aConnectorWithAddressPartsOrArgumentsIsInstalledWithTheOperatorsValues() throws Exception {
        scriptCatalogs();
        JsonNode tenant = getJson("/api/skill-hub/marketplaces/mcp-registry/item?ref=io.github.acme/tenant");
        assertThat(tenant.path("inputs").toString()).contains("\"name\":\"tenant\"").contains("\"kind\":\"URL\"")
                .contains("\"defaultValue\":\"eu\"");
        JsonNode added = postJson("/api/skill-hub/connectors/install", json.writeValueAsString(Map.of(
                "provider", "mcp-registry", "ref", "io.github.acme/tenant", "name", "acme-tenant", "targets", List.of("claude-code"),
                "values", Map.of("tenant", "my team"))));
        assertThat(outcome(added, "claude-code").path("status").asString()).isEqualTo("INSTALLED");
        assertThat(clis.calls().getLast().arguments()).contains("https://mcp.acme.example/my%20team/mcp?region=eu");

        JsonNode cli = getJson("/api/skill-hub/marketplaces/mcp-registry/item?ref=io.github.acme/cli");
        assertThat(cli.path("inputs").toString()).contains("\"name\":\"workspace\"").contains("\"kind\":\"ARG\"")
                .contains("\"name\":\"port\"");
        clis.reset();
        mockMvc.perform(post("/api/skill-hub/connectors/install").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"mcp-registry\",\"ref\":\"io.github.acme/cli\",\"targets\":[\"claude-code\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Serve un valore per workspace"));
        JsonNode withArgs = postJson("/api/skill-hub/connectors/install", json.writeValueAsString(Map.of(
                "provider", "mcp-registry", "ref", "io.github.acme/cli", "targets", List.of("claude-code"),
                "values", Map.of("workspace", "C:/work/shop"))));
        assertThat(outcome(withArgs, "claude-code").path("status").asString()).isEqualTo("INSTALLED");
        List<String> arguments = clis.calls().getLast().arguments();
        assertThat(arguments.subList(arguments.indexOf("@acme/cli-mcp@1.0.0"), arguments.size()))
                .containsExactly("@acme/cli-mcp@1.0.0", "--mode", "readonly", "C:/work/shop", "--port", "7000");

        JsonNode evil = getJson("/api/skill-hub/marketplaces/mcp-registry/item?ref=io.github.acme/evilargs");
        assertThat(evil.path("targets")).isEmpty();
        assertThat(evil.path("warnings").toString()).contains("non sa passare");
    }

    /**
     * PHASE 32 (TD-90): an admin takes a plugin or a connector out of its application the way the application
     * does it -- its CLI, or its configuration file with a backup; what another source brings is only said.
     */
    @Test
    void aPluginOrAConnectorIsTakenOutOfItsApplicationByAnAdmin() throws Exception {
        Path plugin = home.resolve(".claude/plugins/cache/claude-plugins-official/frontend-design/1.0.0");
        write(plugin.resolve(".claude-plugin/plugin.json"), "{\"name\": \"frontend-design\", \"version\": \"1.0.0\"}");
        write(home.resolve(".claude/plugins/installed_plugins.json"), "{\"plugins\": {\"frontend-design@claude-plugins-official\": "
                + "[{\"installPath\": \"" + plugin.toString().replace("\\", "\\\\") + "\", \"version\": \"1.0.0\"}]}}");
        write(home.resolve(".claude.json"), "{\"mcpServers\": {\"files\": {\"command\": \"npx\", \"args\": [\"-y\", \"@acme/files-mcp\"]}}}");
        write(desktopHome.resolve("claude_desktop_config.json"),
                "{\"mcpServers\": {\"blender\": {\"command\": \"uvx\"}, \"notes\": {\"command\": \"npx\"}}}");
        JsonNode installed = getJson("/api/skill-hub/installed?rescan=true");
        String admin = com.aicompany.backend.support.AdminSession.bearer(mockMvc);

        String pluginId = itemId(installed, "claude-code", "PLUGIN", "frontend-design");
        mockMvc.perform(delete("/api/skill-hub/installed/" + pluginId)).andExpect(status().isForbidden());
        assertThat(clis.calls()).isEmpty();

        JsonNode removed = json.readTree(mockMvc.perform(delete("/api/skill-hub/installed/" + pluginId)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(removed.path("status").asString()).isEqualTo("REMOVED");
        assertThat(clis.calls().getLast()).isEqualTo(new FakeHubCommands.Call(Tool.CLAUDE,
                List.of("plugin", "uninstall", "frontend-design@claude-plugins-official", "--scope", "user", "--json")));

        JsonNode connector = json.readTree(mockMvc.perform(delete("/api/skill-hub/installed/"
                        + itemId(installed, "claude-code", "CONNECTOR", "files"))
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(connector.path("status").asString()).isEqualTo("REMOVED");
        assertThat(clis.calls().getLast()).isEqualTo(new FakeHubCommands.Call(Tool.CLAUDE, List.of("mcp", "remove", "files", "-s", "user")));

        JsonNode desktop = json.readTree(mockMvc.perform(delete("/api/skill-hub/installed/"
                        + itemId(installed, "claude-desktop", "CONNECTOR", "notes"))
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(desktop.path("status").asString()).isEqualTo("REMOVED");
        assertThat(Files.readString(desktopHome.resolve("claude_desktop_config.json"))).contains("blender").doesNotContain("notes");
        try (Stream<Path> backups = Files.list(Path.of(aicosHome).resolve("backups/app-config"))) {
            assertThat(backups.map(f -> f.getFileName().toString())).anyMatch(n -> n.startsWith("claude-desktop-claude_desktop_config.json-"));
        }
    }

    /**
     * PHASE 32 (TD-90): a connector the Hub installed from the MCP Registry is offered again when the registry
     * has a newer version; with nothing to ask the operator it is reinstalled in place, otherwise it says why not.
     */
    @Test
    void aConnectorTheHubInstalledIsOfferedAgainWhenItsCatalogHasANewerVersion() throws Exception {
        scriptCatalogs();
        web.answer(REGISTRY + "io.github.acme%2Fplain", plain("1.0.0"));
        assertThat(postJson("/api/skill-hub/connectors/install", json.writeValueAsString(Map.of("provider", "mcp-registry",
                "ref", "io.github.acme/plain", "targets", List.of("claude-code")))).path("results").path(0).path("status").asString())
                .isEqualTo("INSTALLED");
        postJson("/api/skill-hub/connectors/install", json.writeValueAsString(Map.of("provider", "mcp-registry",
                "ref", "io.github.acme/files", "targets", List.of("claude-code"), "values", Map.of("ACME_KEY", SECRET))));
        assertThat(updateIds(postJson("/api/skill-hub/updates/check", "{}"))).noneMatch(id -> id.startsWith("conn:"));

        markets.clearCaches();
        web.answer(REGISTRY + "io.github.acme%2Fplain", plain("1.1.0"));
        web.answer(REGISTRY + "io.github.acme%2Ffiles", files("0.4.0"));
        JsonNode check = postJson("/api/skill-hub/updates/check", "{}");
        JsonNode plainItem = updateItem(check, "conn:claude-code:plain");
        assertThat(plainItem.path("from").asString()).isEqualTo("1.0.0");
        assertThat(plainItem.path("to").asString()).isEqualTo("1.1.0");
        assertThat(plainItem.path("applicable").asBoolean()).isTrue();
        assertThat(updateItem(check, "conn:claude-code:files").path("applicable").asBoolean()).isFalse();

        clis.reset();
        JsonNode applied = postJson("/api/skill-hub/updates/apply", json.writeValueAsString(Map.of("ids", List.of("conn:claude-code:plain"),
                "overwrite", false)));
        assertThat(applied.path("results").path(0).path("outcome").asString()).isEqualTo("UPDATED");
        assertThat(clis.calls()).extracting(FakeHubCommands.Call::arguments).first().isEqualTo(List.of("mcp", "remove", "plain", "-s", "user"));
        assertThat(clis.calls().getLast().arguments()).contains("@acme/plain-mcp@1.1.0");
        assertThat(updateIds(postJson("/api/skill-hub/updates/check", "{}"))).doesNotContain("conn:claude-code:plain");
    }

    /**
     * PHASE 32 (TD-75): a Claude Code plugin with a newer version in its marketplace is updated from the Hub,
     * with Claude Code's own CLI; a command the marketplace declares for it is shown, never run from here.
     */
    @Test
    void aClaudeCodePluginIsUpdatedFromTheHubWithItsCli() throws Exception {
        Path plugin = home.resolve(".claude/plugins/cache/claude-plugins-official/frontend-design/1.0.0");
        write(plugin.resolve(".claude-plugin/plugin.json"), "{\"name\": \"frontend-design\", \"version\": \"1.0.0\"}");
        write(home.resolve(".claude/plugins/installed_plugins.json"), "{\"plugins\": {\"frontend-design@claude-plugins-official\": "
                + "[{\"installPath\": \"" + plugin.toString().replace("\\", "\\\\") + "\", \"version\": \"1.0.0\"}]}}");
        write(home.resolve(".claude/plugins/marketplaces/claude-plugins-official/.claude-plugin/marketplace.json"),
                "{\"name\": \"claude-plugins-official\", \"plugins\": [{\"name\": \"frontend-design\", \"version\": \"1.1.0\"}]}");
        getJson("/api/skill-hub/installed?rescan=true");

        String id = "plugin:claude-code:frontend-design@claude-plugins-official";
        JsonNode item = updateItem(postJson("/api/skill-hub/updates/check", "{}"), id);
        assertThat(item.path("applicable").asBoolean()).isTrue();
        assertThat(item.path("to").asString()).isEqualTo("1.1.0");

        clis.answer(Tool.CLAUDE, List.of("plugin", "update"), 0, "{\"command\":\"update\",\"outcome\":\"updated\"}\n");
        JsonNode applied = postJson("/api/skill-hub/updates/apply", json.writeValueAsString(Map.of("ids", List.of(id), "overwrite", false)));
        assertThat(applied.path("results").path(0).path("outcome").asString()).isEqualTo("UPDATED");
        assertThat(clis.calls().getLast()).isEqualTo(new FakeHubCommands.Call(Tool.CLAUDE,
                List.of("plugin", "update", "frontend-design@claude-plugins-official", "--scope", "user", "--json")));

        // A command the marketplace declares is not run from here.
        clis.reset();
        clis.answer(Tool.CLAUDE, List.of("plugin", "update"), 1, "{\"command\":\"update\",\"outcome\":\"failed\","
                + "\"shownCommand\":{\"command\":\"npx -y setup\",\"sha256\":\"" + "b".repeat(64) + "\"}}\n");
        JsonNode declared = postJson("/api/skill-hub/updates/apply", json.writeValueAsString(Map.of("ids", List.of(id), "overwrite", false)));
        assertThat(declared.path("results").path(0).path("outcome").asString()).isEqualTo("FAILED");
        assertThat(declared.path("results").path(0).path("message").asString()).contains("confermalo in Claude Code");
        assertThat(clis.calls()).hasSize(1);
    }

    private static String plain(String version) {
        return "{\"servers\": [{\"server\": {\"name\": \"io.github.acme/plain\", \"description\": \"Plain\", \"version\": \"" + version
                + "\", \"packages\": [{\"registryType\": \"npm\", \"identifier\": \"@acme/plain-mcp\", \"version\": \"" + version
                + "\", \"transport\": {\"type\": \"stdio\"}}]}}]}";
    }

    private static String files(String version) {
        return "{\"servers\": [{\"server\": {\"name\": \"io.github.acme/files\", \"description\": \"Files\", \"version\": \"" + version
                + "\", \"packages\": [{\"registryType\": \"npm\", \"identifier\": \"@acme/files-mcp\", \"version\": \"" + version
                + "\", \"transport\": {\"type\": \"stdio\"}, \"environmentVariables\": [{\"name\": \"ACME_KEY\", \"isRequired\": true,"
                + " \"isSecret\": true}]}]}}]}";
    }

    private static List<String> updateIds(JsonNode updates) {
        List<String> out = new ArrayList<>();
        updates.path("items").forEach(i -> out.add(i.path("id").asString()));
        return out;
    }

    private static JsonNode updateItem(JsonNode updates, String id) {
        for (JsonNode item : updates.path("items")) {
            if (id.equals(item.path("id").asString())) {
                return item;
            }
        }
        throw new AssertionError("no update " + id + " in " + updateIds(updates));
    }

    private static String itemId(JsonNode installed, String app, String kind, String name) {
        for (JsonNode s : installed.path("skills")) {
            if (app.equals(s.path("app").asString()) && kind.equals(s.path("kind").asString()) && name.equals(s.path("name").asString())) {
                return s.path("id").asString();
            }
        }
        throw new AssertionError("no " + kind + " " + name + " in " + app);
    }

    // --- fixtures -------------------------------------------------------------------------

    private void scriptCatalogs() {
        web.answer(CLAUDE_CATALOG, "{\"name\": \"claude-plugins-official\", \"plugins\": ["
                + "{\"name\": \"frontend-design\", \"description\": \"Distinctive UI design\", \"category\": \"development\","
                + " \"author\": {\"name\": \"Anthropic\"}, \"source\": \"./plugins/frontend-design\"},"
                + "{\"name\": \"stripe\", \"description\": \"Payments\", \"source\": {\"source\": \"url\", \"url\": \"https://github.com/stripe/ai.git\"}},"
                + "{\"name\": \"../evil\", \"description\": \"design\"}]}");
        web.answer("https://raw.githubusercontent.com/anthropics/claude-plugins-official/HEAD/plugins/frontend-design/.claude-plugin/plugin.json",
                "{\"name\": \"frontend-design\", \"version\": \"1.0.0\"}");
        web.answer("https://raw.githubusercontent.com/anthropics/knowledge-work-plugins/HEAD/.claude-plugin/marketplace.json",
                "{\"name\": \"knowledge-work-plugins\", \"plugins\": [{\"name\": \"sales\", \"description\": \"Sales\", \"source\": \"./sales\"}]}");
        web.answer(CODEX_CATALOG, "{\"name\": \"openai-curated\", \"plugins\": [{\"name\": \"linear\", "
                + "\"source\": {\"source\": \"local\", \"path\": \"./plugins/linear\"}, \"category\": \"Productivity\"}]}");
        web.answer("https://raw.githubusercontent.com/openai/plugins/HEAD/plugins/linear/.codex-plugin/plugin.json",
                "{\"name\": \"linear\", \"version\": \"1.0.0\", \"interface\": {\"displayName\": \"Linear\", \"shortDescription\": \"Issues and projects\"}}");
        String servers = "{\"servers\": ["
                + "{\"server\": {\"name\": \"io.github.acme/notes\", \"title\": \"Acme Notes\", \"description\": \"Notes\", \"version\": \"1.2.0\","
                + " \"repository\": {\"url\": \"https://github.com/acme/notes\"}, \"remotes\": [{\"type\": \"streamable-http\","
                + " \"url\": \"https://mcp.acme.example/mcp\", \"headers\": [{\"name\": \"Authorization\", \"isRequired\": true,"
                + " \"isSecret\": true, \"description\": \"Bearer token\"}]}]}},"
                + "{\"server\": {\"name\": \"io.github.acme/files\", \"description\": \"Files\", \"version\": \"0.3.0\", \"packages\": ["
                + "{\"registryType\": \"npm\", \"identifier\": \"@acme/files-mcp\", \"version\": \"0.3.0\", \"transport\": {\"type\": \"stdio\"},"
                + " \"environmentVariables\": [{\"name\": \"ACME_KEY\", \"isRequired\": true, \"isSecret\": true, \"description\": \"API key\"},"
                + " {\"name\": \"ACME_REGION\", \"default\": \"eu\"}]}]}},"
                + "{\"server\": {\"name\": \"io.github.acme/templated\", \"description\": \"Per tenant\", \"remotes\": ["
                + "{\"type\": \"streamable-http\", \"url\": \"https://{tenant}.acme.example/mcp\"}]}},"
                + "{\"server\": {\"name\": \"io.github.acme/tenant\", \"description\": \"Per tenant, declared\", \"remotes\": ["
                + "{\"type\": \"streamable-http\", \"url\": \"https://mcp.acme.example/{tenant}/mcp?region={region}\","
                + " \"variables\": {\"tenant\": {\"description\": \"Your tenant\"}, \"region\": {\"default\": \"eu\"}}}]}},"
                + "{\"server\": {\"name\": \"io.github.acme/cli\", \"description\": \"With arguments\", \"packages\": ["
                + "{\"registryType\": \"npm\", \"identifier\": \"@acme/cli-mcp\", \"version\": \"1.0.0\", \"transport\": {\"type\": \"stdio\"},"
                + " \"packageArguments\": [{\"type\": \"positional\", \"valueHint\": \"workspace\", \"isRequired\": true},"
                + " {\"type\": \"named\", \"name\": \"--mode\", \"value\": \"readonly\"},"
                + " {\"type\": \"named\", \"name\": \"--port\", \"default\": \"7000\"}]}]}},"
                + "{\"server\": {\"name\": \"io.github.acme/evilargs\", \"description\": \"Bad flag\", \"packages\": ["
                + "{\"registryType\": \"npm\", \"identifier\": \"@acme/evil-mcp\", \"transport\": {\"type\": \"stdio\"},"
                + " \"packageArguments\": [{\"type\": \"named\", \"name\": \"--x&rem\", \"isRequired\": true}]}]}}]}";
        web.answer(REGISTRY + "acme", servers);
        for (String ref : List.of("io.github.acme%2Fnotes", "io.github.acme%2Ffiles", "io.github.acme%2Ftemplated",
                "io.github.acme%2Ftenant", "io.github.acme%2Fcli", "io.github.acme%2Fevilargs")) {
            web.answer(REGISTRY + ref, servers);
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private static JsonNode result(JsonNode search, String provider, String ref) {
        List<String> seen = new ArrayList<>();
        for (JsonNode r : search.path("results")) {
            if (r.path("provider").asString().equals(provider) && r.path("ref").asString().equals(ref)) {
                return r;
            }
            seen.add(r.path("provider").asString() + " " + r.path("ref").asString());
        }
        throw new AssertionError("No " + provider + " " + ref + " in " + seen + " " + search.path("errors"));
    }

    private static JsonNode plan(JsonNode preview, String app) {
        for (JsonNode target : preview.path("targets")) {
            if (target.path("app").asString().equals(app)) {
                return target;
            }
        }
        throw new AssertionError("No plan for " + app);
    }

    private static JsonNode outcome(JsonNode response, String target) {
        for (JsonNode r : response.path("results").isMissingNode() ? response : response.path("results")) {
            if (r.path("target").asString().equals(target)) {
                return r;
            }
        }
        throw new AssertionError("No outcome for " + target + " in " + response);
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, String body) throws Exception {
        MvcResult result = mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
