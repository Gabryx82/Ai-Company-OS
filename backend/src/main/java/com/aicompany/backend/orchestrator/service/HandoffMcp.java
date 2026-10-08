package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.harness.model.HarnessResource;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PHASE 32 (TD-48): the MCP servers an agent is equipped with, configured where
 * the tool that takes the handoff reads them -- the project's own file
 * ({@code .mcp.json} for Claude Code, {@code opencode.json} for OpenCode), never
 * the user's. A server is configured only when its entry says how to start it: one
 * line, a known launcher, plain arguments, no placeholder but the project folder.
 * Everything else -- a token to ask for, a server configured on this machine by
 * hand -- is listed in the handoff as to be configured by hand. An entry already in
 * the file is the operator's, and is never changed.
 */
final class HandoffMcp {

    record Server(String key, String name, String command, List<String> args) {
    }

    /** {@code file} null when the target has no project file the Hub knows how to write. */
    record Plan(@Nullable String file, List<Server> servers, List<String> manual) {
    }

    /** Where each target reads the servers of a project. */
    static final Map<String, String> PROJECT_FILES = Map.of("claude-code", ".mcp.json", "opencode", "opencode.json");

    private static final Set<String> LAUNCHERS = Set.of("npx", "uvx", "bunx", "pnpm", "node", "deno", "python", "python3",
            "docker", "dnx");
    /** An argument as a command line gets it: no spaces, quotes, redirections or shell metacharacters. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9@:/\\\\._=,+~-]+");
    private static final List<String> FOLDER_PLACEHOLDERS = List.of("<cartella>", "<folder>", "<progetto>");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HandoffMcp() {
    }

    static Plan plan(String target, Path folder, List<HarnessResource> equipped) {
        List<Server> servers = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        String file = PROJECT_FILES.get(target);
        for (HarnessResource r : equipped) {
            if (r.getKind() != HarnessResource.Kind.MCP) {
                continue;
            }
            Optional<Server> server = file == null ? Optional.empty() : serverOf(r, folder);
            if (server.isPresent()) {
                servers.add(server.get());
            } else {
                manual.add(r.getName());
            }
        }
        return new Plan(file, servers, manual);
    }

    /** A server's command from its entry's configuration, when it is one plain command line. */
    static Optional<Server> serverOf(HarnessResource resource, Path folder) {
        String configuration = resource.getConfiguration();
        if (configuration == null || configuration.isBlank() || configuration.strip().contains("\n")) {
            return Optional.empty();
        }
        String[] parts = configuration.strip().split("\\s+");
        if (!LAUNCHERS.contains(parts[0])) {
            return Optional.empty();
        }
        List<String> args = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            if (FOLDER_PLACEHOLDERS.contains(arg.toLowerCase(java.util.Locale.ROOT))) {
                args.add(folder.toString());
            } else if (PLAIN.matcher(arg).matches()) {
                args.add(arg);
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(new Server(resource.getKey(), resource.getName(), parts[0], List.copyOf(args)));
    }

    /**
     * The project file with the servers it lacks added; {@code null} when there is
     * nothing to add. A file that is not a JSON object is left alone (null too).
     */
    static @Nullable String merge(String file, Optional<String> existing, List<Server> servers, List<String> added) {
        ObjectNode root;
        try {
            JsonNode parsed = existing.filter(s -> !s.isBlank()).map(JSON::readTree).orElse(JSON.createObjectNode());
            if (!(parsed instanceof ObjectNode object)) {
                return null;
            }
            root = object;
        } catch (RuntimeException e) {
            return null;
        }
        boolean claude = file.equals(".mcp.json");
        String section = claude ? "mcpServers" : "mcp";
        if (!claude && !root.has("$schema")) {
            root.put("$schema", "https://opencode.ai/config.json");
        }
        JsonNode current = root.path(section);
        if (!(current instanceof ObjectNode) && !current.isMissingNode()) {
            return null;   // a section of another shape: the operator's, untouched
        }
        ObjectNode block = current instanceof ObjectNode object ? object : root.putObject(section);
        for (Server server : servers) {
            if (block.has(server.key())) {
                continue;
            }
            ObjectNode entry = block.putObject(server.key());
            if (claude) {
                entry.put("command", server.command());
                ArrayNode args = entry.putArray("args");
                server.args().forEach(args::add);
            } else {
                entry.put("type", "local");
                ArrayNode command = entry.putArray("command");
                command.add(server.command());
                server.args().forEach(command::add);
                entry.put("enabled", true);
            }
            added.add(server.key());
        }
        return added.isEmpty() ? null : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }
}
