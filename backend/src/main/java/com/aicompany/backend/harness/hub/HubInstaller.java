package com.aicompany.backend.harness.hub;

import com.aicompany.backend.harness.hub.ExtensionMarketplaces.ConnectorSpec;
import com.aicompany.backend.harness.hub.ExtensionMarketplaces.ConnectorInput;
import com.aicompany.backend.harness.hub.ExtensionMarketplaces.PluginEntry;
import com.aicompany.backend.harness.hub.HubCommands.Tool;
import com.aicompany.backend.harness.hub.SkillMarketplaces.Provider;
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
    private static final Duration SHORT = Duration.ofSeconds(120);
    private static final Duration LONG = Duration.ofSeconds(300);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public enum InstallStatus { INSTALLED, NEEDS_CONFIRMATION, MANUAL, FAILED }

    /** How an item would reach one application, shown before anything runs. */
    public record InstallPlan(String app, String appName, boolean available, String method, String command, String note) {
    }

    /** {@code installName}: the name the applications will know it by (a connector's can be changed at install). */
    public record ExtensionPreview(String provider, String ref, String kind, String name, String installName, String description,
                                   String version, String sourceUrl, Map<String, String> details, List<ConnectorInput> inputs, List<InstallPlan> targets,
                              List<String> warnings) {
    }

    /**
     * What happened in one application. {@code confirmation} is the sha256 the
     * operator confirms when a plugin's marketplace declares a command to run;
     * {@code software} the Software Hub key to open for a manual step.
     */
    public record InstallOutcome(String target, InstallStatus status, String message, String command, String output,
                          String confirmation, String software) {
    }

    private final SkillMarketplaces markets;
    private final ExtensionMarketplaces extensions;
    private final HubCommands commands;
    private final SkillLocations locations;
    private final SkillScanner scanner;
    private final Path backups;
    private final boolean windows;

    public HubInstaller(SkillMarketplaces markets, ExtensionMarketplaces extensions, HubCommands commands,
                        SkillLocations locations, SkillScanner scanner,
                        @Value("${aicos.home:${user.home}/.aicos}") String home,
                        @Value("${aicos.hub.windows:}") String windows) {
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
            return new ExtensionPreview(provider.key(), ref, "PLUGIN", title, plugin.name(), description, plugin.version(),
                    Optional.ofNullable(plugin.homepage()).orElse(plugin.sourceUrl()), details, List.of(), targets,
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

    public InstallOutcome installPlugin(String providerKey, String ref, String target, String confirmation) {
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
        InstallOutcome outcome = switch (target) {
            case "claude-code" -> claudePlugin(plugin, confirmation);
            case "codex" -> codexPlugin(plugin);
            case "claude-desktop" -> new InstallOutcome(target, InstallStatus.MANUAL,
                    "Claude Desktop installa i plugin dal tuo account: apri Claude → Personalizza → Plugin, cerca «" + plugin.name()
                            + "» nel marketplace " + plugin.marketplace() + " e installalo.", null, null, null, "claude-desktop");
            default -> throw HubProblemException.readOnly("«" + target + "» non è una destinazione dei plugin");
        };
        rescan(outcome);
        return outcome;
    }

    private InstallOutcome claudePlugin(PluginEntry plugin, String confirmation) {
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
        HubCommands.Result result = commands.run(Tool.CLAUDE, install, LONG);
        output.append(result.output());
        String display = "claude " + String.join(" ", install);
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

    private InstallOutcome codexPlugin(PluginEntry plugin) {
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
            return new InstallOutcome("codex", InstallStatus.INSTALLED, "Installato in ChatGPT · Codex: «" + id + "»", display,
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
                if (input.required()) {
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
                out.add(redact(connector(spec, name, target, given, replace), given));
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
            return desktopConfig(spec, name, values, replace);
        }
        Tool tool = "codex".equals(target) ? Tool.CODEX : Tool.CLAUDE;
        List<String> arguments = connectorArguments(spec, name, target, values);
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
            case "codex" -> spec.remote() && spec.inputs().stream().anyMatch(i -> "HEADER".equals(i.kind()) && i.required())
                    ? new InstallPlan(target, "ChatGPT · Codex", commands.executable(Tool.CODEX).isPresent(), "MANUAL", null,
                    "Questo connettore remoto vuole un'intestazione d'autenticazione: aggiungilo da Codex → Impostazioni → MCP.")
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
                args.add(spec.url());
            } else {
                args.add("--transport");
                args.add(spec.transport());
                args.add(name);
                args.add(spec.url());
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
        args.addAll(launch(spec.command()));
        return args;
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
            List<String> launch = launch(spec.command());
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

    // --- helpers ---------------------------------------------------------------------------

    private Set<String> claudeMarketplaces() {
        Set<String> out = new HashSet<>();
        SkillScanner.json(locations.home().resolve(".claude").resolve("plugins").resolve("known_marketplaces.json"))
                .ifPresent(known -> out.addAll(known.propertyNames()));
        return out;
    }

    private void rescan(InstallOutcome outcome) {
        if (outcome.status() == InstallStatus.INSTALLED) {
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
