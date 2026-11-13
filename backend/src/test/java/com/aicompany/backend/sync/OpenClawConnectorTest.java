package com.aicompany.backend.sync;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.harness.hub.HubCommands.Tool;
import com.aicompany.backend.support.FakeHubCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 37 (ADR-043 §3): an agent of AI Company OS as an OpenClaw agent. OpenClaw's
 * configuration is written by OpenClaw -- the connector runs its commands and never
 * opens the file that holds the gateway's token -- and of the workspace only the files
 * the connector made are ever rewritten.
 */
class OpenClawConnectorTest {

    @TempDir
    Path home;

    private final FakeHubCommands cli = new FakeHubCommands();
    private final com.aicompany.backend.support.FakeSyncHttp http = new com.aicompany.backend.support.FakeSyncHttp();
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> patches = new ArrayList<>();
    private Path state;
    private OpenClawConnector connector;

    @BeforeEach
    void anOpenClawWithItsOwnCoordinator() throws IOException {
        state = Files.createDirectories(home.resolve(".openclaw"));
        Files.writeString(state.resolve("openclaw.json"), "{\"gateway\":{\"auth\":{\"token\":\"never-read\"}}}");
        Path coordinator = Files.createDirectories(state.resolve("workspace/coordinator"));
        Files.writeString(coordinator.resolve("AGENTS.md"), "# Chief of staff operating program\n\nOwn the outcome.\n");
        http.answer("GET", "/healthz", 200, "{\"ok\":true}");
        connector = new OpenClawConnector(cli, http, state.toString(), "http://127.0.0.1:18789/", home.toString(), false);
        listing(listed("coordinator", "Chief of Staff", coordinator, true));
        // The patch file is the caller's and is removed after the command: read while it runs.
        cli.onRun(call -> {
            if (call.arguments().size() == 4 && call.arguments().subList(0, 3).equals(List.of("config", "patch", "--file"))) {
                try {
                    patches.add(Files.readString(Path.of(call.arguments().get(3))));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    private String listed(String id, String identity, Path workspace, boolean isDefault) {
        return "{\"id\":\"" + id + "\",\"identityName\":\"" + identity + "\",\"workspace\":"
                + json.writeValueAsString(workspace.toString()) + ",\"isDefault\":" + isDefault + "}";
    }

    /** What {@code openclaw agents list --json} prints: a log line first, as the real command does. */
    private void listing(String... agents) {
        cli.answerFirst(Tool.OPENCLAW, List.of("agents", "list"), 0,
                "\u001B[33m[state/agent-db]\u001B[39m agent database clean-close receipt: written\n[" + String.join(",", agents) + "]\n");
    }

    /** {@code openclaw agents add} as it behaves: it makes the workspace, with its own default files. */
    private void addingCreatesTheWorkspace() {
        cli.onRun(call -> {
            if (call.arguments().size() > 4 && call.arguments().subList(0, 2).equals(List.of("agents", "add"))) {
                try {
                    Path workspace = Files.createDirectories(Path.of(call.arguments().get(4)));
                    Files.writeString(workspace.resolve("AGENTS.md"), "# Default OpenClaw program\n");
                    Files.writeString(workspace.resolve("BOOTSTRAP.md"), "# Who am I?\n");
                    cli.answer(Tool.OPENCLAW, List.of("agents", "add"), 0, "[state/agent-db] written\n{\"agentId\":\""
                            + call.arguments().get(2) + "\",\"workspace\":" + json.writeValueAsString(workspace.toString()) + "}\n");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    private static AgentProfile frontend() {
        Agent agent = new Agent("Frontend Developer", "Sviluppatore frontend", "React e TypeScript", "ollama:qwen3.5:9b");
        agent.configureProfile(null, "Console", "Scrivi componenti accessibili.", "Implementare le viste.",
                "Non toccare il backend.", "Diff e test.", "Tipi dal contratto", null);
        agent.configureBinding("Costruisce la console dell'operatore.", "react, typescript", "ollama:qwen3.5:9b", "engine");
        return AgentProfile.of(agent, List.of(new AgentProfile.Skill("react-typescript", "React e TypeScript",
                "Componenti funzionali e hook.", "Usa i tipi generati.")));
    }

    @Test
    void theToolsOwnAgentsAreListedFromItsCommandWithTheirOperatingProgram() {
        List<AgentSyncConnector.ExternalAgent> agents = connector.list();

        assertThat(agents).hasSize(1);
        assertThat(agents.getFirst().externalId()).isEqualTo("coordinator");
        assertThat(agents.getFirst().name()).isEqualTo("Chief of Staff");
        assertThat(agents.getFirst().ours()).isFalse();
        assertThat(agents.getFirst().instructions()).contains("Own the outcome.");
        assertThat(connector.defaultAgent()).contains("coordinator");
    }

    @Test
    void aNewAgentIsCreatedByOpenClawDescribedThroughItsPatchAndGivenItsFiles() throws IOException {
        addingCreatesTheWorkspace();

        AgentSyncConnector.Published published = connector.publish(frontend(), null);

        assertThat(published.externalId()).isEqualTo("aicos-frontend-developer");
        Path workspace = state.resolve("workspace/aicos-frontend-developer");
        assertThat(cli.calls()).extracting(FakeHubCommands.Call::arguments).contains(
                List.of("agents", "add", "aicos-frontend-developer", "--workspace", workspace.toString(), "--non-interactive", "--json"));
        // Identity and description go through `config patch`, under this agent's entry and nowhere else.
        assertThat(patches).hasSize(1);
        JsonNode patch = json.readTree(patches.getFirst());
        assertThat(patch.propertyNames()).containsExactly("agents");
        assertThat(patch.path("agents").path("entries").propertyNames()).containsExactly("aicos-frontend-developer");
        JsonNode entry = patch.path("agents").path("entries").path("aicos-frontend-developer");
        assertThat(entry.path("identity").path("name").asString()).isEqualTo("Frontend Developer");
        assertThat(entry.path("description").asString()).isEqualTo("Costruisce la console dell'operatore.");
        assertThat(entry.has("skills")).as("the allow-list would take OpenClaw's own skills away").isFalse();
        assertThat(entry.has("model")).isFalse();
        // The workspace: the operating program is the agent's, the first-run ritual is gone.
        String program = Files.readString(workspace.resolve("AGENTS.md"));
        assertThat(program).startsWith(OpenClawConnector.MARKER).contains("Frontend Developer")
                .contains("Scrivi componenti accessibili.").contains("Non toccare il backend.")
                .contains("`react-typescript`").contains(".aicos/handoffs/").doesNotContain("Default OpenClaw program");
        assertThat(Files.readString(workspace.resolve("IDENTITY.md"))).startsWith(OpenClawConnector.MARKER).contains("Frontend Developer");
        assertThat(workspace.resolve("SOUL.md")).exists();
        assertThat(workspace.resolve("BOOTSTRAP.md")).doesNotExist();
        // The patch file did not outlive the command.
        assertThat(cli.calls()).filteredOn(c -> c.arguments().getFirst().equals("config"))
                .allSatisfy(c -> assertThat(Path.of(c.arguments().get(3))).doesNotExist());
    }

    @Test
    void anAgentAlreadyThereIsNotAddedAgainAndAFileTheOperatorRewroteStaysTheirs() throws IOException {
        Path workspace = Files.createDirectories(state.resolve("workspace/aicos-frontend-developer"));
        Files.writeString(workspace.resolve("AGENTS.md"), "# Il mio programma\n\nScritto a mano in OpenClaw.\n");
        Files.writeString(workspace.resolve("IDENTITY.md"), OpenClawConnector.MARKER + "\n\nvecchia identità\n");
        Files.writeString(workspace.resolve("BOOTSTRAP.md"), "# lasciato dall'operatore\n");
        listing(listed("coordinator", "Chief of Staff", state.resolve("workspace/coordinator"), true),
                listed("aicos-frontend-developer", "Frontend Developer", workspace, false));

        AgentSyncConnector.Published published = connector.publish(frontend(), "aicos-frontend-developer");

        assertThat(cli.calls()).noneMatch(c -> c.arguments().subList(0, 2).equals(List.of("agents", "add")));
        assertThat(Files.readString(workspace.resolve("AGENTS.md"))).isEqualTo("# Il mio programma\n\nScritto a mano in OpenClaw.\n");
        assertThat(Files.readString(workspace.resolve("IDENTITY.md"))).contains("Frontend Developer").doesNotContain("vecchia identità");
        assertThat(workspace.resolve("BOOTSTRAP.md")).exists();
        assertThat(published.notes()).anyMatch(note -> note.contains("AGENTS.md") && note.contains("resta com'è"));
    }

    @Test
    void whatOpenClawRefusesIsSaidAndNoFileIsWritten() {
        cli.answer(Tool.OPENCLAW, List.of("agents", "add"), 1, "Error: agent id is reserved");

        assertThatThrownBy(() -> connector.publish(frontend(), null))
                .isInstanceOf(SyncProblemException.class).hasMessageContaining("agent id is reserved");

        assertThat(state.resolve("workspace/aicos-frontend-developer")).doesNotExist();
        assertThat(patches).isEmpty();
    }

    @Test
    void onlyAnAgentAiCompanyOsMadeIsEverRemoved() {
        assertThatThrownBy(() -> connector.remove("coordinator")).isInstanceOf(SyncProblemException.class);
        assertThat(cli.calls()).noneMatch(c -> c.arguments().contains("delete"));

        listing(listed("aicos-frontend-developer", "Frontend Developer", state.resolve("workspace/aicos-frontend-developer"), false));
        connector.remove("aicos-frontend-developer");

        assertThat(cli.calls()).extracting(FakeHubCommands.Call::arguments)
                .contains(List.of("agents", "delete", "aicos-frontend-developer", "--force", "--json"));
    }

    @Test
    void aRemovalOpenClawLeftHalfDoneIsSaidEvenThoughItsCommandSucceeded() {
        listing(listed("aicos-frontend-developer", "Frontend Developer", state.resolve("workspace/aicos-frontend-developer"), false));
        cli.answer(Tool.OPENCLAW, List.of("agents", "delete"), 0,
                "{\"removed\":[],\"failed\":[{\"path\":\"C:/x/agent\",\"reason\":\"EPERM: operation not permitted, rename\"}]}\n"
                        + "Warning: cron cleanup was skipped\n");

        assertThatThrownBy(() -> connector.remove("aicos-frontend-developer"))
                .isInstanceOf(SyncProblemException.class).hasMessageContaining("EPERM").hasMessageContaining("cestino");
    }

    @Test
    void anIdOpenClawCouldNotTakeNeverReachesItsCommandLine() {
        assertThatThrownBy(() -> connector.publish(frontend(), "aicos-x & calc"))
                .isInstanceOf(SyncProblemException.class);

        assertThat(cli.calls()).isEmpty();
    }

    @Test
    void withoutTheCliOrWithoutAConfigurationNothingIsAsked() throws IOException {
        cli.missing(Tool.OPENCLAW);
        assertThat(connector.status().availability()).isEqualTo(AgentSyncConnector.Availability.NOT_INSTALLED);
        assertThat(connector.list()).isEmpty();
        assertThatThrownBy(() -> connector.publish(frontend(), null)).isInstanceOf(SyncProblemException.class);

        cli.reset();
        Files.delete(state.resolve("openclaw.json"));
        assertThat(connector.status().availability()).isEqualTo(AgentSyncConnector.Availability.NOT_CONFIGURED);
        assertThat(connector.list()).isEmpty();
        assertThat(cli.calls()).isEmpty();
    }

    @Test
    void withTheGatewayDownNoCommandIsRunAndTheStatusSaysHowToStartIt() {
        http.reset();

        AgentSyncConnector.Status status = connector.status();

        assertThat(status.availability()).isEqualTo(AgentSyncConnector.Availability.UNREACHABLE);
        assertThat(status.detail()).contains("gateway di OpenClaw è spento");
        assertThat(status.hint()).contains("openclaw daemon start");
        assertThat(connector.list()).isEmpty();
        assertThatThrownBy(() -> connector.publish(frontend(), null)).isInstanceOf(SyncProblemException.class);
        // A command would wait half a minute for the gateway: none was started.
        assertThat(cli.calls()).isEmpty();
        // The probe asks the gateway's health and carries no credential.
        assertThat(http.calls()).isNotEmpty().allSatisfy(call -> {
            assertThat(call.path()).isEqualTo("/healthz");
            assertThat(call.bearer()).isNull();
        });

        // A setup without a gateway is not asked.
        OpenClawConnector without = new OpenClawConnector(cli, http, state.toString(), " ", home.toString(), false);
        assertThat(without.status().available()).isTrue();
    }

    /**
     * ADR-046: readings run in the background. One that started before a publish ends after it with what OpenClaw
     * had before: kept over the publish, it would take the new agent away until the next reading.
     */
    @Test
    void aReadingThatStartedBeforeAPublishDoesNotTakeTheNewAgentAway() throws Exception {
        addingCreatesTheWorkspace();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        cli.onRun(call -> {
            if (call.arguments().subList(0, 2).equals(List.of("agents", "list")) && first.getAndSet(false)) {
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        var background = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        connector.background(background);
        try {
            assertThat(connector.known().known()).isFalse();
            connector.publish(frontend(), null);

            release.countDown();
            for (int i = 0; i < 100 && connector.known().reading(); i++) {
                Thread.sleep(20);
            }

            assertThat(connector.known().reading()).isFalse();
            assertThat(connector.known().agents()).extracting(AgentSyncConnector.ExternalAgent::externalId)
                    .containsExactly("coordinator", "aicos-frontend-developer");
        } finally {
            release.countDown();
            background.shutdown();
        }
    }

    @Test
    void theListingIsAskedOnceForSeveralReadsAndAgainAfterTheConnectorChangesIt() {
        addingCreatesTheWorkspace();

        connector.list();
        connector.list();
        connector.defaultAgent();
        assertThat(cli.calls()).filteredOn(c -> c.arguments().subList(0, 2).equals(List.of("agents", "list"))).hasSize(1);

        connector.publish(frontend(), null);
        List<AgentSyncConnector.ExternalAgent> after = connector.list();

        // The connector's own writes keep the listing true: the new agent is in it, with its identity, unasked.
        assertThat(cli.calls()).filteredOn(c -> c.arguments().subList(0, 2).equals(List.of("agents", "list"))).hasSize(1);
        assertThat(after).extracting(AgentSyncConnector.ExternalAgent::externalId).containsExactly("coordinator", "aicos-frontend-developer");
        assertThat(after.get(1).name()).isEqualTo("Frontend Developer");
        assertThat(after.get(1).ours()).isTrue();
        connector.remove("aicos-frontend-developer");
        assertThat(connector.list()).extracting(AgentSyncConnector.ExternalAgent::externalId).containsExactly("coordinator");
    }

    @Test
    void theFingerprintMovesWithWhatTheAgentIsToldAndOnlyWithThat() {
        AgentProfile agent = frontend();
        Agent changed = new Agent("Frontend Developer", "Sviluppatore frontend", "React e TypeScript", "ollama:qwen3.5:4b");
        changed.configureProfile(null, "Console", "Scrivi componenti accessibili.", "Implementare le viste.",
                "Non toccare il backend.", "Diff e test.", "Tipi dal contratto", null);
        changed.configureBinding("Costruisce la console dell'operatore.", "react, typescript", "ollama:qwen3.5:4b", "engine");
        AgentProfile otherModel = AgentProfile.of(changed, agent.skills());
        changed.configureProfile(null, "Console", "Scrivi componenti accessibili e veloci.", "Implementare le viste.",
                "Non toccare il backend.", "Diff e test.", "Tipi dal contratto", null);
        AgentProfile otherPrompt = AgentProfile.of(changed, agent.skills());

        // OpenClaw keeps its own model: a change of the agent's model here is nothing it is given.
        assertThat(connector.fingerprint(otherModel)).isEqualTo(connector.fingerprint(agent));
        assertThat(connector.fingerprint(otherPrompt)).isNotEqualTo(connector.fingerprint(agent));
    }
}
