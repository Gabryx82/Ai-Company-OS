package com.aicompany.backend.sync;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 37 (ADR-043): an agent of AI Company OS as a LiteLLM alias -- a name any
 * OpenAI-compatible client can ask the gateway for, behind which stands the agent's
 * model. An alias names a model and nothing else: this version of LiteLLM (1.97) has
 * no system prompt in its configuration, and its agent registry needs a database the
 * gateway here runs without.
 *
 * <p>The gateway reads one YAML file at start. The connector writes its aliases in a
 * block of that file between two marker lines, at the end of {@code model_list}, and
 * touches nothing outside it: the file is edited as text, so the operator's entries,
 * comments and order stay exactly as they were. Before the first block is written the
 * file is copied beside itself; before any write the result is parsed, and refused
 * unless every model that was there is still there.
 */
@Component
public class LiteLlmConnector implements AgentSyncConnector {

    public static final String KEY = "litellm";

    static final String BEGIN = "# >>> AI Company OS: agenti (ADR-043) -- blocco generato, si riscrive a ogni sincronizzazione";
    static final String END = "# <<< AI Company OS";

    private static final Pattern MODEL_LIST = Pattern.compile("(?m)^model_list:[ \\t]*(#.*)?$");
    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("(?m)^[A-Za-z_][A-Za-z0-9_-]*:");
    private static final Pattern ENTRY_INDENT = Pattern.compile("(?m)^([ \\t]*)-[ \\t]+model_name:");
    private static final Pattern OLLAMA_BASE = Pattern.compile(
            "(?m)^[ \\t]+model:[ \\t]*ollama(?:_chat)?/\\S+[ \\t]*\\r?\\n[ \\t]+api_base:[ \\t]*(\\S+)");
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$");
    private static final Pattern ALIAS = Pattern.compile("^aicos-[a-z0-9-]{1,60}$");

    /** One alias of the block. */
    record Alias(String name, String model, String apiBase, String description) {
    }

    private final @Nullable Path configured;
    private final Path home;
    private final String defaultOllamaBase;

    LiteLlmConnector(@Value("${aicos.sync.litellm.config:}") String config,
                     @Value("${aicos.sync.litellm.ollama-api-base:http://host.docker.internal:11434}") String ollamaBase,
                     @Value("${aicos.hub.home:${user.home}}") String home) {
        this.configured = config == null || config.isBlank() ? null : Path.of(config.strip()).toAbsolutePath().normalize();
        this.home = Path.of(home).toAbsolutePath().normalize();
        this.defaultOllamaBase = ollamaBase.strip();
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "LiteLLM";
    }

    @Override
    public String shape() {
        return "un alias del gateway: un nome che ogni client compatibile con OpenAI può chiedere, dietro cui sta il modello dell'agente";
    }

    /**
     * The gateway's configuration file: the one {@code AICOS_LITELLM_CONFIG} names, or
     * the one 3D Omniverse keeps for the gateway it starts.
     */
    Optional<Path> file() {
        Path candidate = configured != null ? configured
                : home.resolve("3D Omniverse").resolve("config").resolve("litellm_config.yaml");
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public Status status() {
        Optional<Path> file = file();
        if (file.isEmpty()) {
            return configured != null
                    ? new Status(Availability.NOT_CONFIGURED, "Il file di configurazione di LiteLLM non c'è: " + configured + ".",
                    "Correggi AICOS_LITELLM_CONFIG in %USERPROFILE%\\.aicos\\local.env e riavvia AI Company OS.")
                    : new Status(Availability.NOT_INSTALLED, "Nessun gateway LiteLLM trovato su questo computer.",
                    "Se ne usi uno, indica il suo file: AICOS_LITELLM_CONFIG=<percorso di config.yaml> in "
                            + "%USERPROFILE%\\.aicos\\local.env.");
        }
        if (!Files.isWritable(file.get())) {
            return new Status(Availability.NOT_CONFIGURED, "Il file " + file.get() + " non è scrivibile.",
                    "Rendilo scrivibile per il tuo utente, poi torna qui.");
        }
        return Status.available("Gli alias si scrivono in " + file.get() + ".");
    }

    @Override
    public Optional<String> notApplicable(AgentProfile agent) {
        if (agent.model() == null) {
            return Optional.of("L'agente non ha un modello: un alias deve indicarne uno.");
        }
        if (!"ollama".equals(agent.provider()) || agent.modelName() == null
                || !MODEL_NAME.matcher(agent.modelName()).matches()) {
            return Optional.of("Il modello dell'agente (" + agent.model() + ") non è un modello locale di Ollama: "
                    + "l'alias non saprebbe dove instradarlo.");
        }
        return Optional.empty();
    }

    @Override
    public String fingerprint(AgentProfile agent) {
        // The address of Ollama is the gateway's, read from its file: not part of what the agent is.
        return Fingerprint.of("litellm/1", agent.modelName(), oneLine(agent.summary()));
    }

    @Override
    public Published publish(AgentProfile agent, @Nullable String externalId) {
        Path file = requireFile();
        notApplicable(agent).ifPresent(why -> {
            throw SyncProblemException.refused(why);
        });
        String alias = externalId == null ? agent.slug() : externalId;
        if (!ALIAS.matcher(alias).matches()) {
            throw SyncProblemException.refused("'" + alias + "' is not an alias AI Company OS writes in LiteLLM");
        }
        String text = read(file);
        Map<String, Alias> block = aliases(text);
        Alias wanted = new Alias(alias, "ollama_chat/" + agent.modelName(), ollamaBase(text), oneLine(agent.summary()));
        boolean changed = !wanted.equals(block.get(alias));
        List<String> notes = new ArrayList<>();
        if (changed) {
            boolean first = !text.contains(BEGIN);
            block.put(alias, wanted);
            String rewritten = withBlock(text, block);
            requireSameModels(text, rewritten, block.keySet());
            if (first) {
                notes.add("Prima di scriverlo la prima volta ho copiato il file in " + backup(file).getFileName() + ".");
            }
            write(file, rewritten);
            notes.add("Il gateway legge il file all'avvio: riavvialo (per esempio «docker restart litellm-gateway») "
                    + "perché veda l'alias «" + alias + "».");
        }
        return new Published(alias, fingerprint(agent), changed, notes);
    }

    @Override
    public List<ExternalAgent> list() {
        Optional<Path> file = file();
        if (file.isEmpty()) {
            return List.of();
        }
        return aliases(read(file.get())).values().stream()
                .map(a -> new ExternalAgent(a.name(), a.name(), a.description(), null, true)).toList();
    }

    @Override
    public void remove(String externalId) {
        Path file = requireFile();
        String text = read(file);
        Map<String, Alias> block = aliases(text);
        if (block.remove(externalId) == null) {
            return;
        }
        String rewritten = withBlock(text, block);
        requireSameModels(text, rewritten, block.keySet());
        write(file, rewritten);
    }

    // --- the block ----------------------------------------------------------------------

    /** The aliases of the block, in the order they stand; empty when there is no block. */
    static Map<String, Alias> aliases(String text) {
        Map<String, Alias> out = new LinkedHashMap<>();
        int begin = text.indexOf(BEGIN);
        int end = begin < 0 ? -1 : text.indexOf(END, begin);
        if (begin < 0 || end < 0) {
            return out;
        }
        Matcher entry = Pattern.compile("(?m)^[ \\t]*-[ \\t]+model_name:[ \\t]*(\\S+)[ \\t]*\\r?\\n"
                + "[ \\t]+litellm_params:[ \\t]*\\r?\\n"
                + "[ \\t]+model:[ \\t]*(\\S+)[ \\t]*\\r?\\n"
                + "[ \\t]+api_base:[ \\t]*(\\S+)[ \\t]*\\r?\\n"
                + "[ \\t]+model_info:[ \\t]*\\r?\\n"
                + "[ \\t]+description:[ \\t]*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text.substring(begin, end));
        while (entry.find()) {
            out.put(entry.group(1), new Alias(entry.group(1), entry.group(2), entry.group(3), unescape(entry.group(4))));
        }
        return out;
    }

    /** The file with the block holding exactly these aliases -- or without a block, when there are none. */
    static String withBlock(String text, Map<String, Alias> aliases) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        Matcher indentOf = ENTRY_INDENT.matcher(text);
        String indent = indentOf.find() ? indentOf.group(1) : "  ";

        String without = text;
        int begin = text.indexOf(BEGIN);
        if (begin >= 0) {
            int end = text.indexOf(END, begin);
            if (end < 0) {
                throw SyncProblemException.refused("Il blocco di AI Company OS nel file di LiteLLM non ha la riga di chiusura: "
                        + "sistemalo a mano prima di sincronizzare.");
            }
            int lineStart = text.lastIndexOf('\n', begin) + 1;
            int lineEnd = text.indexOf('\n', end);
            lineEnd = lineEnd < 0 ? text.length() : lineEnd + 1;
            // The blank line the block was given above it goes with it.
            String before = text.substring(0, lineStart);
            if (before.endsWith(eol + eol)) {
                before = before.substring(0, before.length() - eol.length());
            }
            without = before + text.substring(lineEnd);
        }
        if (aliases.isEmpty()) {
            return without;
        }

        Matcher list = MODEL_LIST.matcher(without);
        if (!list.find()) {
            throw SyncProblemException.refused("Il file di LiteLLM non ha una voce «model_list:» a cui aggiungere gli alias.");
        }
        Matcher next = TOP_LEVEL_KEY.matcher(without);
        int insertAt = next.find(list.end()) ? next.start() : without.length();
        // Back over the blank lines that separate model_list from what follows: the block joins the list.
        int tail = insertAt;
        while (tail > list.end() && (without.charAt(tail - 1) == '\n' || without.charAt(tail - 1) == '\r'
                || without.charAt(tail - 1) == ' ' || without.charAt(tail - 1) == '\t')) {
            tail--;
        }
        StringBuilder block = new StringBuilder(eol).append(eol).append(indent).append(BEGIN).append(eol);
        for (Alias alias : aliases.values()) {
            block.append(indent).append("- model_name: ").append(alias.name()).append(eol)
                    .append(indent).append("  litellm_params:").append(eol)
                    .append(indent).append("    model: ").append(alias.model()).append(eol)
                    .append(indent).append("    api_base: ").append(alias.apiBase()).append(eol)
                    .append(indent).append("  model_info:").append(eol)
                    .append(indent).append("    description: \"").append(escape(alias.description())).append('"').append(eol);
        }
        block.append(indent).append(END).append(eol);
        String rest = without.substring(insertAt);
        return without.substring(0, tail) + block + (rest.isEmpty() ? "" : eol + rest);
    }

    /**
     * The check before any write: the rewritten file is YAML, and its {@code model_list}
     * holds every model the file had outside the block, plus the block's aliases.
     */
    static void requireSameModels(String before, String after, Set<String> wantedAliases) {
        Set<String> had = modelNames(before);
        Set<String> has = modelNames(after);
        Set<String> expected = new LinkedHashSet<>(had);
        expected.removeIf(name -> ALIAS.matcher(name).matches() && aliases(before).containsKey(name));
        expected.addAll(wantedAliases);
        if (!has.equals(expected)) {
            throw SyncProblemException.refused("La riscrittura del file di LiteLLM cambierebbe i suoi modelli in modo "
                    + "inatteso: non ho scritto niente.");
        }
    }

    /** The {@code model_name} of every entry of {@code model_list}, as LiteLLM would read the file. */
    static Set<String> modelNames(String text) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            throw SyncProblemException.refused("Il file di LiteLLM non è YAML valido: " + firstLine(e.getMessage()));
        }
        Set<String> names = new LinkedHashSet<>();
        if (root instanceof Map<?, ?> map && map.get("model_list") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> model && model.get("model_name") != null) {
                    if (!names.add(String.valueOf(model.get("model_name")))) {
                        throw SyncProblemException.refused("Il file di LiteLLM avrebbe due modelli di nome «"
                                + model.get("model_name") + "».");
                    }
                }
            }
        }
        return names;
    }

    /** Where the gateway reaches Ollama: as its own entries say, since it may run in a container. */
    private String ollamaBase(String text) {
        Matcher base = OLLAMA_BASE.matcher(text);
        return base.find() ? base.group(1) : defaultOllamaBase;
    }

    // --- the file -----------------------------------------------------------------------

    private Path requireFile() {
        Status status = status();
        if (!status.available()) {
            throw SyncProblemException.unavailable(status.detail() + (status.hint() == null ? "" : " " + status.hint()));
        }
        return file().orElseThrow();
    }

    private static String read(Path file) {
        try {
            if (Files.size(file) > 1024 * 1024) {
                throw SyncProblemException.refused("Il file di LiteLLM è più grande di 1 MB: non lo riscrivo.");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw SyncProblemException.unavailable("Il file di LiteLLM non si legge: " + e.getMessage());
        }
    }

    /** In place, so that a container that mounts the file keeps seeing it. */
    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw SyncProblemException.unavailable("Il file di LiteLLM non si scrive: " + e.getMessage());
        }
    }

    private static Path backup(Path file) {
        Path copy = file.resolveSibling(file.getFileName() + ".aicos-backup-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        try {
            return Files.copy(file, copy);
        } catch (IOException e) {
            throw SyncProblemException.unavailable("Non riesco a copiare il file di LiteLLM prima di scriverlo: " + e.getMessage());
        }
    }

    private static String oneLine(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() <= 200 ? line : line.substring(0, 199).stripTrailing() + "…";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String firstLine(@Nullable String message) {
        if (message == null) {
            return "errore di lettura";
        }
        int at = message.indexOf('\n');
        return at < 0 ? message : message.substring(0, at);
    }
}
