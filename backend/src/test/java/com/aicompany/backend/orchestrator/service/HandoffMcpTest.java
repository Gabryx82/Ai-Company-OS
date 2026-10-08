package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.harness.model.HarnessResource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 (TD-48): an MCP entry becomes a server in the project's file only when
 * it says how to start it with one plain command; the operator's entries stay.
 */
class HandoffMcpTest {

    private static final Path FOLDER = Path.of("C:/work/officina");
    private final JsonMapper json = JsonMapper.builder().build();

    private static HarnessResource mcp(String key, String configuration) {
        HarnessResource r = new HarnessResource(key, HarnessResource.Kind.MCP, key, null, List.of(), null, null, configuration);
        return r;
    }

    @Test
    void onlyOnePlainCommandLineWithAKnownLauncherIsAServer() {
        assertThat(HandoffMcp.serverOf(mcp("fs", "npx -y @modelcontextprotocol/server-filesystem <cartella>"), FOLDER))
                .hasValueSatisfying(s -> {
                    assertThat(s.command()).isEqualTo("npx");
                    assertThat(s.args()).containsExactly("-y", "@modelcontextprotocol/server-filesystem", FOLDER.toString());
                });
        // Inert payloads: what must never reach a command line.
        for (String refused : new String[] {"Richiede un token GitHub dell'operatore.", "npx tool &rem", "npx tool >NUL",
                "npx \"a b\"", "cmd /c echo", "npx tool\nnpx other", "npx tool <TOKEN>", "npx tool $HOME", "npx tool |more"}) {
            assertThat(HandoffMcp.serverOf(mcp("x", refused), FOLDER)).as(refused).isEmpty();
        }
        assertThat(HandoffMcp.serverOf(mcp("x", null), FOLDER)).isEmpty();
    }

    @Test
    void theServersMissingAreAddedAndTheOperatorsEntriesStay() {
        List<HandoffMcp.Server> servers = List.of(new HandoffMcp.Server("fs", "Filesystem", "npx", List.of("-y", "pkg")),
                new HandoffMcp.Server("mine", "Mine", "npx", List.of("other")));
        List<String> added = new ArrayList<>();
        String merged = HandoffMcp.merge(".mcp.json",
                Optional.of("{\"mcpServers\": {\"mine\": {\"command\": \"uvx\", \"args\": [\"kept\"]}}, \"other\": 1}"), servers, added);
        JsonNode root = json.readTree(merged);
        assertThat(added).containsExactly("fs");
        assertThat(root.path("mcpServers").path("mine").path("command").asString()).isEqualTo("uvx");
        assertThat(root.path("mcpServers").path("fs").path("args").toString()).isEqualTo("[\"-y\",\"pkg\"]");
        assertThat(root.path("other").asInt()).isEqualTo(1);

        List<String> again = new ArrayList<>();
        assertThat(HandoffMcp.merge(".mcp.json", Optional.of(merged), servers, again)).as("nothing to add").isNull();
        assertThat(HandoffMcp.merge(".mcp.json", Optional.of("[1, 2]"), servers, new ArrayList<>())).as("not ours").isNull();
        assertThat(HandoffMcp.merge(".mcp.json", Optional.of("{\"mcpServers\": []}"), servers, new ArrayList<>())).isNull();
    }

    @Test
    void openCodeReadsALocalCommandAsOneArray() {
        String merged = HandoffMcp.merge("opencode.json", Optional.empty(),
                List.of(new HandoffMcp.Server("fs", "Filesystem", "npx", List.of("-y", "pkg"))), new ArrayList<>());
        JsonNode root = json.readTree(merged);
        assertThat(root.path("$schema").asString()).isEqualTo("https://opencode.ai/config.json");
        assertThat(root.path("mcp").path("fs").path("type").asString()).isEqualTo("local");
        assertThat(root.path("mcp").path("fs").path("command").toString()).isEqualTo("[\"npx\",\"-y\",\"pkg\"]");
        assertThat(root.path("mcp").path("fs").path("enabled").asBoolean()).isTrue();
    }

    @Test
    void aTargetWithoutAProjectFileListsEveryServerAsManual() {
        HandoffMcp.Plan plan = HandoffMcp.plan("codex", FOLDER, List.of(mcp("fs", "npx -y pkg")));
        assertThat(plan.file()).isNull();
        assertThat(plan.servers()).isEmpty();
        assertThat(plan.manual()).containsExactly("fs");
    }
}
