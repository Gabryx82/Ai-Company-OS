package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.hub.ExtensionMarketplaces.ConnectorSpec;
import com.aicompany.backend.harness.hub.ExtensionMarketplaces.ConnectorInput;
import com.aicompany.backend.harness.hub.ExtensionMarketplaces.PluginEntry;
import com.aicompany.backend.harness.hub.HubCommands.Tool;
import com.aicompany.backend.harness.hub.SkillMarketplaces.Provider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Installs plugins and connectors from the Marketplace into the applications
 * (ADR-036 §6b) -- through the applications' own CLIs wherever one exists:
 * <ul>
 *   <li>Claude Code: {@code claude plugin marketplace add} / {@code claude plugin install},
 *       {@code claude mcp add --scope user};</li>
 *   <li>ChatGPT · Codex: {@code codex plugin add}, {@code codex mcp add};</li>
 *   <li>Claude Desktop: a local connector into its {@code claude_desktop_config.json}
 *       (a backup is kept); its plugins and remote connectors come with the account,
 *       so the Hub says where to add them in the app.</li>
 * </ul>
 * Every command is an argument list built from validated parts -- no shell. A
 * plugin whose marketplace declares a command to run is installed only after the
 * operator saw that command and confirmed it ({@code --accept-command}). The
 * values given to a connector (API keys) are passed to the application and
 * never stored, logged or returned by AI Company OS.
 */
@Service
public class HubInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(HubInstaller.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");
    /** PHASE 32 (TD-88): a plugin option, or a bundled server's ({@code server.key}). */
    private static final Pattern OPTION = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$");
    private static final Duration SHORT = Duration.ofSeconds(120);
    private static final Duration LONG = Duration.ofSeconds(300);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** {@code REMOVED}: PHASE 32 (TD-90), taken out of the application. */
    public enum InstallStatus { INSTALLED, NEEDS_CONFIRMATION, MANUAL, FAILED, REMOVED }

    /** PHASE 32 (TD-90): a connector the Hub installed whose catalog has a newer version. */
    public record ConnectorUpdate(String app, String name, String reference, @Nullable String from, String to, boolean applicable) {
    }

    /** How an item would reach one application, shown before anything runs. */
    public record InstallPlan(String app, String appName, boolean available, String method, @Nullable String command, @Nullable String note) {
    }

    /** {@code installName}: the name the applications will know it by (a connector's can be changed at install). */
    public record ExtensionPreview(String provider, String ref, String kind, String name, String installName, String description,
                                   @Nullable String version, @Nullable String sourceUrl, Map<String, String> details, List<ConnectorInput> inputs, List<InstallPlan> targets,
                              List<String> warnings) {
    }

    /**
     * What happened in one application. {@code confirmation} is the sha256 the
     * operator confirms when a plugin's marketplace declares a command to run;
     * {@code software} the Software Hub key to open for a manual step.
     */
    public record InstallOutcome(String target, InstallStatus status, String message, @Nullable String command, @Nullable String output,
                          @Nullable String confirmation, @Nullable String software) {
    }

    private final SkillMarketplaces markets;
    private final ExtensionMarketplaces extensions;
    private final HubCommands commands;
    private final SkillLocations locations;
    private final SkillScanner scanner;
    private final Path backups;
    private final boolean windows;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public HubInstaller(SkillMarketplaces markets, ExtensionMarketplaces extensions, HubCommands commands,
                        SkillLocations locations, SkillScanner scanner,
                        @Value("${aicos.home:${user.home}/.aicos}") String home,
                        @Value("${aicos.hub.windows:}") String windows, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.markets = markets;
        this.extensions = extensions;
        this.commands = commands;
        this.locations = locations;
        this.scanner = scanner;
        this.backups = Path.of(home).toAbsolutePath().normalize().resolve("backups").resolve("app-config");
        this.windows = windows == null || windows.isBlank()
                ? System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") : Boolean.parseBoolean(windows);
    }

    // --- preview -------------------------------------------------------------------------

    public ExtensionPreview preview(String providerKey, String ref) {
        Provider provider = markets.provider(providerKey);
        if ("PLUGIN".equals(provider.kindOrSkill())) {
            PluginEntry plugin = extensions.plugin(provider, ref);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Marketplace", plugin.marketplace());
            Optional.ofNullable(plugin.category()).ifPresent(c -> details.put("Categoria", c));
            Optional.ofNullable(plugin.author()).ifPresent(a -> details.put("Autore", a));
            String description = plugin.description();
            String title = plugin.name();
            Optional<JsonNode> manifest = extensions.pluginManifest(provider, plugin);
            if (manifest.isPresent()) {
                JsonNode face = manifest.get().path("interface");
                title = Optional.ofNullable(text(face, "displayName")).orElse(title);
                description = Optional.ofNullable(description).or(() -> Optional.ofNullable(text(face, "shortDescription")))
                        .orElse(text(manifest.get(), "description"));
                Optional.ofNullable(text(manifest.get(), "version")).ifPresent(v -> details.put("Versione", v));
            }
            List<InstallPlan> targets = new ArrayList<>();
            for (String target : provider.targets() == null ? List.<String>of() : provider.targets()) {
                targets.add(pluginPlan(provider, plugin, target));
            }
            List<ConnectorInput> options = manifest.map(HubInstaller::pluginOptions).orElse(List.of());
            return new ExtensionPreview(provider.key(), ref, "PLUGIN", title, plugin.name(), description, plugin.version(),
                    Optional.ofNullable(plugin.homepage()).orElse(plugin.sourceUrl()), details, options, targets,
                    List.of("Un plugin può aggiungere skill, comandi, agenti, hook e server MCP: installalo da fonti di cui ti fidi."));
        }
        if ("CONNECTOR".equals(provider.kindOrSkill())) {
            ConnectorSpec spec = extensions.connector(provider, ref);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("Tipo", spec.transport() == null ? "non installabile da qui" : spec.remote() ? "remoto (" + spec.transport() + ")" : "locale");
            if (spec.url() != null) {
                details.put("Indirizzo", SkillScanner.safeUrl(spec.url()));
            }
            if (spec.packageType() != null) {
                details.put("Avvio", String.join(" ", spec.command()));
            }
            Optional.ofNullable(spec.repository()).ifPresent(r -> details.put("Repository", r));
            List<InstallPlan> targets = new ArrayList<>();
            if (spec.transport() != null) {
                for (String target : provider.targets() == null ? List.<String>of() : provider.targets()) {
                    targets.add(connectorPlan(spec, spec.name(), target, Map.of()));
                }
            }
            List<String> warnings = new ArrayList<>(spec.warnings());
            if (!spec.remote() && spec.transport() != null) {
                warnings.add("Un connettore locale è un programma che l'app avvierà sul tuo PC: installalo da fonti di cui ti fidi.");
            }
            return new ExtensionPreview(provider.key(), ref, "CONNECTOR", Optional.ofNullable(spec.title()).orElse(spec.name()),
                    spec.name(), spec.description(), spec.version(), spec.repository(), details, spec.inputs(), targets, warnings);
        }
        throw HubProblemException.notFound(provider.name() + " offre skill: l'anteprima è quella delle skill");
    }

    // --- plugins ---------------------------------------------------------------------------

    /**
     * PHASE 32 (TD-88): the options a plugin asks for at install -- the {@code userConfig}
     * of its manifest -- as the inputs of the install dialog.
     */
    static List<ConnectorInput> pluginOptions(JsonNode manifest) {
        List<ConnectorInput> out = new ArrayList<>();
        JsonNode config = manifest.has("userConfig") ? manifest.path("userConfig") : manifest.path("user_config");
        for (Map.Entry<String, JsonNode> option : config.properties()) {
            if (!OPTION.matcher(option.getKey()).matches() || !option.getValue().isObject()) {
                continue;
            }
            JsonNode o = option.getValue();
            String description = Optional.ofNullable(text(o, "description")).orElse(text(o, "title"));
            String fallback = o.path("default").isValueNode() && !o.path("default").isNull() ? o.path("default").asString() : null;
            out.add(new ConnectorInput(option.getKey(), "CONFIG", o.path("required").asBoolean(false),
                    o.path("sensitive").asBoolean(false), description, fallback));
        }
        return out;
    }

    public InstallOutcome installPlugin(String providerKey, String ref, String target, String confirmation) {
        return installPlugin(providerKey, ref, target, confirmation, Map.of());
    }

    public InstallOutcome installPlugin(String providerKey, String ref, String target, String confirmation,
                                        Map<String, String> values) {
        Provider provider = markets.provider(providerKey);
        if (!"PLUGIN".equals(provider.kindOrSkill()) || provider.targets() == null || !provider.targets().contains(target)) {
            throw HubProblemException.readOnly("«" + target + "» non è una destinazione di " + provider.name());
        }
        PluginEntry plugin = extensions.plugin(provider, ref);
        if (!REPO.matcher(plugin.repo()).matches() || !SkillMarketplaces.OWNER.matcher(plugin.marketplace()).matches()) {
            throw HubProblemException.packageRefused("Il marketplace di questo plugin non ha un nome valido");
        }
        if (confirmation != null && !confirmation.isBlank() && !SHA.matcher(confirmation).matches()) {
            throw HubProblemException.packageRefused("Conferma non valida");
        }
        Map<String, String> options = pluginValues(provider, plugin, target, values == null ? Map.of() : values);
        InstallOutcome outcome = switch (target) {
            case "claude-code" -> redact(claudePlugin(plugin, confirmation, options), options);
            case "codex" -> codexPlugin(plugin, !options.isEmpty() || !pluginOptions(provider, plugin).isEmpty());
            case "claude-desktop" -> new InstallOutcome(target, InstallStatus.MANUAL,
                    "Claude Desktop installa i plugin dal tuo account: apri Claude → Personalizza → Plugin, cerca «" + plugin.name()
                            + "» nel marketplace " + plugin.marketplace() + " e installalo.", null, null, null, "claude-desktop");
            default -> throw HubProblemException.readOnly("«" + target + "» non è una destinazione dei plugin");
        };
        rescan(outcome);
        return outcome;
    }

    private List<ConnectorInput> pluginOptions(Provider provider, PluginEntry plugin) {
        return extensions.pluginManifest(provider, plugin).map(HubInstaller::pluginOptions).orElse(List.of());
    }

    /**
     * PHASE 32 (TD-88): the options given, checked against the ones the plugin declares
     * before anything runs. Claude Code receives them at install ({@code --config});
     * a required one missing is refused there.
     */
    private Map<String, String> pluginValues(Provider provider, PluginEntry plugin, String target, Map<String, String> values) {
        List<ConnectorInput> declared = pluginOptions(provider, plugin);
        Map<String, String> given = new LinkedHashMap<>();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (declared.stream().noneMatch(i -> i.name().equals(value.getKey()))) {
                throw HubProblemException.packageRefused("Il plugin non dichiara l'opzione «" + value.getKey() + "»");
            }
            String v = value.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            if (v.length() > 4000 || v.chars().anyMatch(c -> c < 0x20)) {
                throw HubProblemException.packageRefused("Il valore di " + value.getKey() + " non è valido");
            }
            given.put(value.getKey(), v);
        }
        if ("claude-code".equals(target)) {
            for (ConnectorInput input : declared) {
                if (input.required() && input.defaultValue() == null && !given.containsKey(input.name())) {
                    throw HubProblemException.packageRefused("Serve un valore per " + input.name());
                }
            }
        }
        return given;
    }

    private InstallOutcome claudePlugin(PluginEntry plugin, String confirmation, Map<String, String> options) {
        String id = plugin.name() + "@" + plugin.marketplace();
        StringBuilder output = new StringBuilder();
        if (!claudeMarketplaces().contains(plugin.marketplace())) {
            List<String> add = List.of("plugin", "marketplace", "add", plugin.repo());
            HubCommands.Result added = commands.run(Tool.CLAUDE, add, LONG);
            output.append(added.output());
            if (!added.ok()) {
                return new InstallOutcome("claude-code", InstallStatus.FAILED, "Claude Code non ha aggiunto il marketplace " + plugin.repo(),
                        "claude " + String.join(" ", add), clip(output.toString()), null, null);
            }
        }
        List<String> install = new ArrayList<>(List.of("plugin", "install", id, "--scope", "user", "--json"));
        if (confirmation != null && !confirmation.isBlank()) {
            install.add("--accept-command");
            install.add(confirmation);
        }
        options.forEach((key, value) -> {
            install.add("--config");
            install.add(key + "=" + value);
        });
        HubCommands.Result result = commands.run(Tool.CLAUDE, install, LONG);
        output.append(result.output());
        String display = "claude " + String.join(" ", mask(install, options));
        JsonNode line = lastJsonLine(result.output());
        String outcome = line == null ? null : text(line, "outcome");
        JsonNode shown = line == null ? null : line.path("shownCommand");
        boolean refused = !result.ok() || "failed".equals(outcome) || (outcome != null && outcome.contains("confirm"));
        if (shown != null && !shown.isMissingNode() && refused) {
            String sha = text(shown, "sha256");
            String command = shownCommand(shown);
            if (sha != null && SHA.matcher(sha).matches() && !sha.equals(confirmation)) {
                return new InstallOutcome("claude-code", InstallStatus.NEEDS_CONFIRMATION,
                        "Il marketplace dichiara un comando da eseguire per installare «" + plugin.name()
                                + "». Leggilo: se lo approvi, conferma.", command, clip(output.toString()), sha, null);
            }
        }
        if (result.ok() && !"failed".equals(outcome)) {
            return new InstallOutcome("claude-code", InstallStatus.INSTALLED, "Installato in Claude Code: «" + id + "»", display,
                    clip(output.toString()), null, null);
        }
        String message = line == null ? null : text(line, "message");
        return new InstallOutcome("claude-code", InstallStatus.FAILED, Optional.ofNullable(message).orElse("Claude Code non ha installato «" + id + "»")
                + (result.timedOut() ? " (tempo scaduto)" : ""), display, clip(output.toString()), null, null);
    }

    private InstallOutcome codexPlugin(PluginEntry plugin, boolean hasOptions) {
        String id = plugin.name() + "@" + plugin.marketplace();
        StringBuilder output = new StringBuilder();
        HubCommands.Result listed = commands.run(Tool.CODEX, List.of("plugin", "marketplace", "list", "--json"), SHORT);
        boolean known = false;
        JsonNode list = lastJsonDocument(listed.output());
        if (list != null) {
            for (JsonNode m : list.path("marketplaces")) {
                known |= plugin.marketplace().equals(text(m, "name"));
            }
        }
        if (!known) {
            List<String> add = List.of("plugin", "marketplace", "add", plugin.repo());
            HubCommands.Result added = commands.run(Tool.CODEX, add, LONG);
            output.append(added.output());
            if (!added.ok()) {
                return new InstallOutcome("codex", InstallStatus.FAILED, "Codex non ha aggiunto il marketplace " + plugin.repo(),
                        "codex " + String.join(" ", add), clip(output.toString()), null, null);
            }
        }
        List<String> install = List.of("plugin", "add", id, "--json");
        HubCommands.Result result = commands.run(Tool.CODEX, install, LONG);
        output.append(result.output());
        String display = "codex " + String.join(" ", install);
        if (result.ok()) {
            return new InstallOutcome("codex", InstallStatus.INSTALLED, "Installato in ChatGPT · Codex: «" + id + "»"
                    + (hasOptions ? ". Le sue opzioni si completano in Codex: la CLI non le riceve" : ""), display,
                    clip(output.toString()), null, null);
        }
        return new InstallOutcome("codex", InstallStatus.FAILED, "Codex non ha installato «" + id + "»" + (result.timedOut() ? " (tempo scaduto)" : "")
                + ". Se richiede un accesso (app collegate), completalo dall'app Codex → Plugin.", display, clip(output.toString()),
                null, "codex");
    }

    private InstallPlan pluginPlan(Provider provider, PluginEntry plugin, String target) {
        String id = plugin.name() + "@" + plugin.marketplace();
        return switch (target) {
            case "claude-code" -> new InstallPlan(target, "Claude Code", commands.executable(Tool.CLAUDE).isPresent(), "CLI",
                    (claudeMarketplaces().contains(plugin.marketplace()) ? "" : "claude plugin marketplace add " + plugin.repo() + "\n")
                            + "claude plugin install " + id + " --scope user", null);
            case "codex" -> new InstallPlan(target, "ChatGPT · Codex", commands.executable(Tool.CODEX).isPresent(), "CLI",
                    "codex plugin add " + id, "Le app collegate chiedono l'accesso al primo uso, in Codex.");
            case "claude-desktop" -> new InstallPlan(target, "Claude Desktop", locations.app("claude-desktop").detected(), "MANUAL",
                    null, "Si installa dall'app: Personalizza → Plugin → " + plugin.marketplace() + " → «" + plugin.name() + "».");
            default -> new InstallPlan(target, target, false, "MANUAL", null, null);
        };
    }

    // --- connectors ----------------------------------------------------------------------------

    public List<InstallOutcome> installConnector(String providerKey, String ref, List<String> targets, String requestedName,
                                          Map<String, String> values, boolean replace) {
        Provider provider = markets.provider(providerKey);
        if (!"CONNECTOR".equals(provider.kindOrSkill())) {
            throw HubProblemException.readOnly(provider.name() + " non offre connettori installabili");
        }
        ConnectorSpec spec = extensions.connector(provider, ref);
        if (spec.transport() == null) {
            throw HubProblemException.packageRefused(String.join("; ", spec.warnings()));
        }
        String name = requestedName == null || requestedName.isBlank() ? spec.name() : requestedName.strip();
        if (!NAME.matcher(name).matches()) {
            throw HubProblemException.packageRefused("Il nome del connettore: lettere, cifre, trattini (fino a 64)");
        }
        Map<String, String> given = new LinkedHashMap<>();
        for (ConnectorInput input : spec.inputs()) {
            String value = values == null ? null : values.get(input.name());
            if (value == null || value.isBlank()) {
                if (input.required() && input.defaultValue() == null) {   // a default is a value (TD-89)
                    throw HubProblemException.packageRefused("Serve un valore per " + input.name());
                }
                continue;
            }
            if (value.length() > 4000 || value.chars().anyMatch(c -> c < 0x20)) {
                throw HubProblemException.packageRefused("Il valore di " + input.name() + " non è valido");
            }
            given.put(input.name(), value);
        }
        List<InstallOutcome> out = new ArrayList<>();
        for (String target : targets == null ? List.<String>of() : targets.stream().distinct().toList()) {
            if (provider.targets() == null || !provider.targets().contains(target)) {
                out.add(new InstallOutcome(target, InstallStatus.FAILED, "Non è una destinazione di questo catalogo", null, null, null, null));
                continue;
            }
            try {
                InstallOutcome outcome = redact(connector(spec, name, target, given, replace), given);
                if (outcome.status() == InstallStatus.INSTALLED) {
                    // PHASE 32 (TD-90): what was installed, for the update check; never the values.
                    jdbc.update("INSERT INTO hub_connector_installs (app, name, provider, reference, version) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (app, name) DO UPDATE SET provider = EXCLUDED.provider, reference = EXCLUDED.reference, "
                            + "version = EXCLUDED.version, installed_at = now()", target, name, provider.key(), ref, spec.version());
                }
                out.add(outcome);
            } catch (HubProblemException e) {
                out.add(new InstallOutcome(target, InstallStatus.FAILED, redact(e.getMessage(), given), null, null, null, null));
            }
        }
        if (out.stream().anyMatch(o -> o.status() == InstallStatus.INSTALLED)) {
            scanner.scan();
        }
        return out;
    }

    private InstallOutcome connector(ConnectorSpec spec, String name, String target, Map<String, String> values, boolean replace) {
        InstallPlan plan = connectorPlan(spec, name, target, values);
        if ("MANUAL".equals(plan.method())) {
            return new InstallOutcome(target, InstallStatus.MANUAL, plan.note(), null, null, null, "claude-desktop".equals(target) ? "claude-desktop"
                    : "codex".equals(target) ? "codex" : null);
        }
        if ("CONFIG".equals(plan.method())) {
            return "codex".equals(target) ? codexConfig(spec, name, values, replace) : desktopConfig(spec, name, values, replace);
        }
        Tool tool = "codex".equals(target) ? Tool.CODEX : Tool.CLAUDE;
        List<String> arguments = connectorArguments(spec, name, target, values);
        if (replace) {
            // PHASE 32 (TD-90): the CLIs refuse a name that exists; replacing removes it first.
            commands.run(tool, tool == Tool.CODEX ? List.of("mcp", "remove", name) : List.of("mcp", "remove", name, "-s", "user"), SHORT);
        }
        HubCommands.Result result = commands.run(tool, arguments, SHORT);
        String display = (tool == Tool.CODEX ? "codex " : "claude ") + String.join(" ", mask(arguments, values));
        String appName = tool == Tool.CODEX ? "ChatGPT · Codex" : "Claude Code";
        if (result.ok()) {
            return new InstallOutcome(target, InstallStatus.INSTALLED, "Connettore «" + name + "» aggiunto a " + appName, display,
                    clip(result.output()), null, null);
        }
        return new InstallOutcome(target, InstallStatus.FAILED, appName + " non ha aggiunto «" + name + "»"
                + (result.output().toLowerCase(Locale.ROOT).contains("already") ? ": esiste già un connettore con questo nome" : ""),
                display, clip(result.output()), null, null);
    }

    private InstallPlan connectorPlan(ConnectorSpec spec, String name, String target, Map<String, String> values) {
        return switch (target) {
            case "claude-code" -> new InstallPlan(target, "Claude Code", commands.executable(Tool.CLAUDE).isPresent(), "CLI",
                    "claude " + String.join(" ", mask(connectorArguments(spec, name, target, values), values)), null);
            case "codex" -> spec.remote() && spec.inputs().stream().anyMatch(i -> "HEADER".equals(i.kind()))
                    ? new InstallPlan(target, "ChatGPT · Codex", locations.app("codex").detected(), "CONFIG",
                    "~/.codex/config.toml → [mcp_servers." + name + "]",
                    "La CLI di Codex non accetta intestazioni: il connettore va nel suo config.toml (con backup), con http_headers.")
                    : new InstallPlan(target, "ChatGPT · Codex", commands.executable(Tool.CODEX).isPresent(), "CLI",
                    "codex " + String.join(" ", mask(connectorArguments(spec, name, target, values), values)), null);
            case "claude-desktop" -> spec.remote()
                    ? new InstallPlan(target, "Claude Desktop", locations.app("claude-desktop").detected(), "MANUAL", null,
                    "I connettori remoti si aggiungono dall'app: Impostazioni → Connettori → Aggiungi connettore personalizzato → "
                            + SkillScanner.safeUrl(spec.url()))
                    : new InstallPlan(target, "Claude Desktop", locations.app("claude-desktop").detected(), "CONFIG",
                    "claude_desktop_config.json → mcpServers." + name,
                    "Aggiunto al file di configurazione di Claude Desktop (con backup): riavvia Claude Desktop per usarlo.");
            default -> new InstallPlan(target, target, false, "MANUAL", null, null);
        };
    }

    /** The CLI arguments of {@code claude mcp add} or {@code codex mcp add}. */
    List<String> connectorArguments(ConnectorSpec spec, String name, String target, Map<String, String> values) {
        List<String> args = new ArrayList<>(List.of("mcp", "add"));
        boolean codex = "codex".equals(target);
        if (!codex) {
            args.add("--scope");
            args.add("user");
        }
        if (spec.remote()) {
            if (codex) {
                args.add(name);
                args.add("--url");
                args.add(filledUrl(spec, values));
            } else {
                args.add("--transport");
                args.add(spec.transport());
                args.add(name);
                args.add(filledUrl(spec, values));
                for (ConnectorInput input : spec.inputs()) {
                    if ("HEADER".equals(input.kind()) && values.containsKey(input.name())) {
                        args.add("--header");
                        args.add(input.name() + ": " + values.get(input.name()));
                    }
                }
            }
            return args;
        }
        if (codex) {
            args.add(name);
        }
        for (ConnectorInput input : spec.inputs()) {
            String value = values.containsKey(input.name()) ? values.get(input.name()) : input.defaultValue();
            if ("ENV".equals(input.kind()) && value != null) {
                args.add(codex ? "--env" : "-e");
                args.add(input.name() + "=" + value);
            }
        }
        if (!codex) {
            args.add(name);
        }
        args.add("--");
        args.addAll(launch(withArguments(spec, values)));
        return args;
    }

    /** PHASE 32 (TD-89): the package's command with the arguments the operator gave, flags first. */
    static List<String> withArguments(ConnectorSpec spec, Map<String, String> values) {
        List<String> out = new ArrayList<>(spec.command());
        for (ConnectorInput input : spec.inputs()) {
            String value = values.containsKey(input.name()) ? values.get(input.name()) : input.defaultValue();
            if (!"ARG".equals(input.kind()) || value == null) {
                continue;
            }
            String flag = spec.flags().getOrDefault(input.name(), "");
            if (!flag.isEmpty()) {
                out.add(flag);
            }
            out.add(value);
        }
        return out;
    }

    /** PHASE 32 (TD-89): a remote address with its declared parts completed, each one percent-encoded. */
    static String filledUrl(ConnectorSpec spec, Map<String, String> values) {
        java.util.regex.Matcher parts = ExtensionMarketplaces.URL_VARIABLE.matcher(spec.url());
        StringBuilder out = new StringBuilder();
        while (parts.find()) {
            String part = parts.group(1);
            String value = values.containsKey(part) ? values.get(part) : spec.inputs().stream()
                    .filter(i -> i.name().equals(part)).map(ConnectorInput::defaultValue).filter(Objects::nonNull).findFirst().orElse("");
            parts.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")));
        }
        parts.appendTail(out);
        if (!ExtensionMarketplaces.REMOTE_URL.matcher(out).matches()) {
            throw HubProblemException.packageRefused("L'indirizzo completato non è valido");
        }
        return out.toString();
    }

    /**
     * PHASE 32 (TD-89): a remote connector that needs headers, into Codex's
     * {@code config.toml} -- its CLI takes no header. A table of that name is
     * replaced only when confirmed; the file is backed up first; nothing else in it
     * is touched.
     */
    private InstallOutcome codexConfig(ConnectorSpec spec, String name, Map<String, String> values, boolean replace) {
        Path file = locations.home().resolve(".codex").resolve("config.toml");
        try {
            List<String> lines = new ArrayList<>(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readString(file, StandardCharsets.UTF_8).lines().toList() : List.of());
            int start = codexTable(lines, name);
            if (start >= 0 && !replace) {
                return new InstallOutcome("codex", InstallStatus.FAILED, "Codex ha già un connettore «" + name
                        + "»: scegli un altro nome o conferma la sostituzione", null, null, null, null);
            }
            if (start >= 0) {
                int end = start + 1;
                while (end < lines.size() && !(lines.get(end).strip().startsWith("[")
                        && !lines.get(end).strip().startsWith("[mcp_servers." + name + "."))) {
                    end++;
                }
                lines.subList(start, end).clear();
            }
            StringBuilder headers = new StringBuilder();
            for (ConnectorInput input : spec.inputs()) {
                if ("HEADER".equals(input.kind()) && values.containsKey(input.name())) {
                    headers.append(headers.isEmpty() ? "" : ", ").append(toml(input.name())).append(" = ")
                            .append(toml(values.get(input.name())));
                }
            }
            while (!lines.isEmpty() && lines.getLast().isBlank()) {
                lines.removeLast();
            }
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add("[mcp_servers." + name + "]");
            lines.add("url = " + toml(filledUrl(spec, values)));
            if (!headers.isEmpty()) {
                lines.add("http_headers = { " + headers + " }");
            }
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                Files.createDirectories(backups);
                Files.copy(file, backups.resolve("codex-config-" + ZonedDateTime.now(ZoneId.systemDefault()).format(STAMP)
                        + ".toml"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return new InstallOutcome("codex", InstallStatus.INSTALLED, "Connettore «" + name
                    + "» aggiunto a ChatGPT · Codex (config.toml, con backup)", "~/.codex/config.toml → [mcp_servers." + name + "]",
                    null, null, null);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not write {}", file, e);
            return new InstallOutcome("codex", InstallStatus.FAILED, "La configurazione di Codex non si può scrivere: "
                    + e.getClass().getSimpleName(), null, null, null, null);
        }
    }

    /** The line of {@code [mcp_servers.<name>]} (bare or quoted), or -1. */
    static int codexTable(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).replace(" ", "");
            if (line.equals("[mcp_servers." + name + "]") || line.equals("[mcp_servers.\"" + name + "\"]")) {
                return i;
            }
        }
        return -1;
    }

    /** A TOML basic string. The values carry no control character (checked before). */
    static String toml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** {@code npx} on Windows runs through {@code cmd /c}, as Claude Code and Codex document. */
    private List<String> launch(List<String> command) {
        if (windows && !command.isEmpty() && command.getFirst().equals("npx")) {
            List<String> out = new ArrayList<>(List.of("cmd", "/c"));
            out.addAll(command);
            return out;
        }
        return command;
    }

    /**
     * A local connector into Claude Desktop's {@code claude_desktop_config.json}
     * -- for the Microsoft Store app, the copy in its package folder -- keeping
     * everything else in the file, and a backup of it.
     */
    private InstallOutcome desktopConfig(ConnectorSpec spec, String name, Map<String, String> values, boolean replace) {
        Path file = locations.claudeDesktopHome().resolve("claude_desktop_config.json");
        try {
            ObjectNode root = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    ? (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)) : JSON.createObjectNode();
            ObjectNode servers = root.has("mcpServers") && root.path("mcpServers").isObject()
                    ? (ObjectNode) root.path("mcpServers") : root.putObject("mcpServers");
            if (servers.has(name) && !replace) {
                return new InstallOutcome("claude-desktop", InstallStatus.FAILED, "Claude Desktop ha già un connettore «" + name
                        + "»: scegli un altro nome o conferma la sostituzione", null, null, null, null);
            }
            List<String> launch = launch(withArguments(spec, values));
            ObjectNode server = JSON.createObjectNode();
            server.put("command", launch.getFirst());
            ArrayNode args = server.putArray("args");
            launch.stream().skip(1).forEach(args::add);
            ObjectNode env = JSON.createObjectNode();
            for (ConnectorInput input : spec.inputs()) {
                String value = values.containsKey(input.name()) ? values.get(input.name()) : input.defaultValue();
                if ("ENV".equals(input.kind()) && value != null) {
                    env.put(input.name(), value);
                }
            }
            if (!env.isEmpty()) {
                server.set("env", env);
            }
            servers.set(name, server);
            Files.createDirectories(file.getParent());
            if (Files.isRegularFile(file)) {
                Files.createDirectories(backups);
                Files.copy(file, backups.resolve("claude_desktop_config-" + ZonedDateTime.now(ZoneId.systemDefault()).format(STAMP)
                        + ".json"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            return new InstallOutcome("claude-desktop", InstallStatus.INSTALLED, "Connettore «" + name
                    + "» aggiunto a Claude Desktop: riavvia l'app per usarlo", "claude_desktop_config.json → mcpServers." + name,
                    null, null, "claude-desktop");
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not write {}", file, e);
            return new InstallOutcome("claude-desktop", InstallStatus.FAILED, "La configurazione di Claude Desktop non si può scrivere: "
                    + e.getClass().getSimpleName(), null, null, null, null);
        }
    }

    // --- taking out (PHASE 32, TD-90) ----------------------------------------------------------

    /**
     * A plugin or a connector taken out of its application the way the application
     * does it: its CLI ({@code claude plugin uninstall}, {@code claude mcp remove},
     * {@code codex plugin remove}, {@code codex mcp remove}) or, for a configuration
     * file, the entry removed with a backup of the file. What another source brings
     * -- a plugin, the account, a project -- is said, not removed.
     */
    public InstallOutcome uninstall(String id) {
        FoundSkill item = scanner.find(id).orElseThrow(() -> HubProblemException.notFound("No item '" + id + "' on this machine"));
        InstallOutcome outcome;
        if (FoundSkill.PLUGIN.equals(item.kind())) {
            outcome = uninstallPlugin(item);
        } else if (FoundSkill.CONNECTOR.equals(item.kind())) {
            outcome = removeConnector(item);
        } else {
            throw HubProblemException.readOnly("Da qui si tolgono plugin e connettori: una copia del Hub si toglie dal suo "
                    + "dettaglio, una skill dell'app dalla sua cartella");
        }
        rescan(outcome);
        return outcome;
    }

    private InstallOutcome uninstallPlugin(FoundSkill item) {
        if (item.builtin()) {
            throw HubProblemException.readOnly("Un plugin interno di Claude Code arriva con l'app: si spegne in Claude Code (/plugin)");
        }
        switch (item.app()) {
            case "claude-code" -> {
                if (item.plugin() == null || item.marketplace() == null || !SkillMarketplaces.SLUG.matcher(item.plugin()).matches()
                        || !SkillMarketplaces.OWNER.matcher(item.marketplace()).matches()) {
                    return new InstallOutcome("claude-code", InstallStatus.MANUAL, "Questo plugin si toglie da Claude Code (/plugin)",
                            null, null, null, null);
                }
                String plugin = item.plugin() + "@" + item.marketplace();
                List<String> args = List.of("plugin", "uninstall", plugin, "--scope", "user", "--json");
                HubCommands.Result result = commands.run(Tool.CLAUDE, args, SHORT);
                JsonNode line = lastJsonLine(result.output());
                boolean failed = !result.ok() || (line != null && "failed".equals(text(line, "outcome")));
                return new InstallOutcome("claude-code", failed ? InstallStatus.FAILED : InstallStatus.REMOVED,
                        failed ? Optional.ofNullable(line == null ? null : text(line, "message")).orElse("Claude Code non ha tolto «" + plugin + "»")
                                : "Disinstallato da Claude Code: «" + plugin + "»", "claude " + String.join(" ", args), clip(result.output()), null, null);
            }
            case "codex" -> {
                Path version = item.folder();
                String name = version.getParent() == null ? "" : version.getParent().getFileName().toString();
                String market = version.getParent() == null || version.getParent().getParent() == null ? ""
                        : version.getParent().getParent().getFileName().toString();
                if (!SkillMarketplaces.SLUG.matcher(name).matches() || !SkillMarketplaces.OWNER.matcher(market).matches()) {
                    return new InstallOutcome("codex", InstallStatus.MANUAL, "Questo plugin si toglie dall'app Codex → Plugin",
                            null, null, null, "codex");
                }
                List<String> args = List.of("plugin", "remove", name + "@" + market);
                HubCommands.Result result = commands.run(Tool.CODEX, args, SHORT);
                return new InstallOutcome("codex", result.ok() ? InstallStatus.REMOVED : InstallStatus.FAILED,
                        result.ok() ? "Disinstallato da ChatGPT · Codex: «" + name + "@" + market + "»"
                                : "Codex non ha tolto «" + name + "@" + market + "»", "codex " + String.join(" ", args),
                        clip(result.output()), null, null);
            }
            default -> {
                return new InstallOutcome(item.app(), InstallStatus.MANUAL, "Questo plugin si toglie dall'app: "
                        + locations.app(item.app()).name() + " → Plugin", null, null, null, item.app());
            }
        }
    }

    private InstallOutcome removeConnector(FoundSkill item) {
        String sources = item.details() == null ? "" : item.details().getOrDefault("Fornito da", "");
        String name = item.name();
        if (!"configurato da te".equals(sources) || !NAME.matcher(name).matches()) {
            String why = sources.startsWith("plugin ") ? "Arriva con un plugin (" + sources + "): si toglie togliendo il plugin"
                    : sources.startsWith("account") ? "Arriva con il tuo account: si toglie dalle impostazioni dell'account"
                    : sources.startsWith("progetto") ? "È configurato in un progetto: si toglie da quella cartella (claude mcp remove)"
                    : sources.startsWith("estensione") ? "È un'estensione dell'app: si toglie dalle sue impostazioni"
                    : "Arriva da più fonti (" + sources + "): si toglie da ciascuna, nell'app";
            return new InstallOutcome(item.app(), InstallStatus.MANUAL, why, null, null, null, item.app());
        }
        InstallOutcome outcome = switch (item.app()) {
            case "claude-code" -> removeWithCli(Tool.CLAUDE, "claude-code", name, List.of("mcp", "remove", name, "-s", "user"));
            case "codex" -> removeWithCli(Tool.CODEX, "codex", name, List.of("mcp", "remove", name));
            case "claude-desktop" -> removeFromJson(locations.claudeDesktopHome().resolve("claude_desktop_config.json"), "claude-desktop", name);
            case "gemini-cli", "antigravity" -> removeFromJson(locations.location(item.location()).root(), item.app(), name);
            default -> new InstallOutcome(item.app(), InstallStatus.MANUAL, "Si toglie dall'app", null, null, null, item.app());
        };
        if (outcome.status() == InstallStatus.REMOVED) {
            jdbc.update("DELETE FROM hub_connector_installs WHERE app = ? AND name = ?", item.app(), name);
        }
        return outcome;
    }

    private InstallOutcome removeWithCli(Tool tool, String app, String name, List<String> args) {
        HubCommands.Result result = commands.run(tool, args, SHORT);
        String appName = locations.app(app).name();
        return new InstallOutcome(app, result.ok() ? InstallStatus.REMOVED : InstallStatus.FAILED,
                result.ok() ? "Connettore «" + name + "» tolto da " + appName : appName + " non ha tolto «" + name + "»",
                (tool == Tool.CODEX ? "codex " : "claude ") + String.join(" ", args), clip(result.output()), null, null);
    }

    /** A server out of a JSON configuration ({@code mcpServers}, or the servers object itself), the file backed up first. */
    private InstallOutcome removeFromJson(Path file, String app, String name) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw HubProblemException.notFound("La configurazione " + file + " non c'è più");
            }
            ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            ObjectNode servers = root.path("mcpServers").isObject() ? (ObjectNode) root.path("mcpServers") : root;
            if (!servers.has(name)) {
                return new InstallOutcome(app, InstallStatus.FAILED, "«" + name + "» non è nella configurazione", null, null, null, null);
            }
            Files.createDirectories(backups);
            Files.copy(file, backups.resolve(app + "-" + file.getFileName() + "-"
                    + ZonedDateTime.now(ZoneId.systemDefault()).format(STAMP)), StandardCopyOption.REPLACE_EXISTING);
            servers.remove(name);
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            return new InstallOutcome(app, InstallStatus.REMOVED, "Connettore «" + name + "» tolto da " + locations.app(app).name()
                    + " (il file precedente è nei backup)" + ("claude-desktop".equals(app) ? ": riavvia l'app" : ""),
                    file.getFileName() + " → " + name, null, null, null);
        } catch (IOException | ClassCastException e) {
            LOG.warn("Could not edit {}", file, e);
            return new InstallOutcome(app, InstallStatus.FAILED, "La configurazione non si può modificare: " + e.getClass().getSimpleName(),
                    null, null, null, null);
        }
    }

    // --- connector updates (PHASE 32, TD-90) ---------------------------------------------------

    /**
     * The connectors the Hub installed from a catalog whose package has a newer
     * version there. A remote one is served where it lives: nothing to update here.
     * {@code applicable}: it needs no value -- the ones the operator gave are never kept.
     */
    public List<ConnectorUpdate> connectorUpdates(List<String> errors) {
        List<ConnectorUpdate> out = new ArrayList<>();
        jdbc.query("SELECT app, name, provider, reference, version FROM hub_connector_installs ORDER BY app, name", (java.sql.ResultSet row) -> {
            String ref = row.getString("reference");
            try {
                ConnectorSpec spec = extensions.connector(markets.provider(row.getString("provider")), ref);
                String installed = row.getString("version");
                if (!spec.remote() && spec.transport() != null && spec.version() != null && !spec.version().equals(installed)) {
                    out.add(new ConnectorUpdate(row.getString("app"), row.getString("name"), ref, installed, spec.version(),
                            spec.inputs().stream().noneMatch(i -> i.required() && i.defaultValue() == null)));
                }
            } catch (RuntimeException e) {
                errors.add("Connettore " + row.getString("name") + ": " + e.getMessage());
            }
        });
        return out;
    }

    /** Installs a connector the Hub installed again, at the catalog's latest version, in place of the old one. */
    public InstallOutcome reinstallConnector(String app, String name) {
        List<java.util.Map.Entry<String, String>> found = jdbc.query("SELECT provider, reference FROM hub_connector_installs WHERE app = ? AND name = ?",
                (row, n) -> java.util.Map.entry(row.getString("provider"), row.getString("reference")), app, name);
        if (found.isEmpty()) {
            throw HubProblemException.notFound("«" + name + "» non è stato installato dal Knowledge Hub in " + app);
        }
        return installConnector(found.getFirst().getKey(), found.getFirst().getValue(), List.of(app), name, Map.of(), true).getFirst();
    }

    // --- helpers ---------------------------------------------------------------------------

    private Set<String> claudeMarketplaces() {
        Set<String> out = new HashSet<>();
        SkillScanner.json(locations.home().resolve(".claude").resolve("plugins").resolve("known_marketplaces.json"))
                .ifPresent(known -> out.addAll(known.propertyNames()));
        return out;
    }

    private void rescan(InstallOutcome outcome) {
        if (outcome.status() == InstallStatus.INSTALLED || outcome.status() == InstallStatus.REMOVED) {
            scanner.scan();
        }
    }

    private static List<String> mask(List<String> arguments, Map<String, String> values) {
        return arguments.stream().map(a -> redact(a, values)).map(a -> a.contains(" ") ? "\"" + a + "\"" : a).toList();
    }

    private static InstallOutcome redact(InstallOutcome outcome, Map<String, String> values) {
        return new InstallOutcome(outcome.target(), outcome.status(), redact(outcome.message(), values), redact(outcome.command(), values),
                redact(outcome.output(), values), outcome.confirmation(), outcome.software());
    }

    /** Every value the operator gave, out of what is shown back. */
    static String redact(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (String value : values.values()) {
            if (value != null && value.length() >= 3) {
                out = out.replace(value, "••••");
            }
        }
        return out;
    }

    private static String clip(String output) {
        if (output == null) {
            return null;
        }
        String stripped = output.strip();
        return stripped.length() > 4000 ? stripped.substring(stripped.length() - 4000) : stripped;
    }

    /** The command a marketplace declares, as Claude Code reported it: its text, or its arguments joined. */
    private static String shownCommand(JsonNode shown) {
        for (String field : List.of("command", "display", "text")) {
            String value = text(shown, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        for (String field : List.of("argv", "args", "command")) {
            if (shown.path(field).isArray()) {
                List<String> parts = new ArrayList<>();
                shown.path(field).forEach(p -> parts.add(p.asString("")));
                return String.join(" ", parts);
            }
        }
        return shown.toString();
    }

    private static JsonNode lastJsonLine(String output) {
        if (output == null) {
            return null;
        }
        List<String> lines = output.lines().map(String::strip).filter(l -> l.startsWith("{") && l.endsWith("}")).toList();
        for (int i = lines.size() - 1; i >= 0; i--) {
            try {
                return JSON.readTree(lines.get(i));
            } catch (RuntimeException e) {
                // not JSON after all
            }
        }
        return null;
    }

    private static JsonNode lastJsonDocument(String output) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JSON.readTree(output.substring(start, end + 1));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.isContainer() ? null : value.asString();
    }
}
