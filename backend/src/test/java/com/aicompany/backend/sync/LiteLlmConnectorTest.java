package com.aicompany.backend.sync;

import com.aicompany.backend.agent.model.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 37 (ADR-043 §5): the gateway's file is somebody else's. The connector adds its
 * aliases in one marked block at the end of {@code model_list} and leaves every other
 * character where it was -- entries, comments, keys, line endings -- and writes nothing
 * at all when the result would not be the file it had plus the aliases it meant.
 */
class LiteLlmConnectorTest {

    /** The shape of the operator's real file: comments, role aliases, a key that is not ours, settings after. */
    private static final String ORIGINAL = """
            # LiteLLM Gateway -- un solo endpoint per tutti i modelli
            #
            # Le chiavi NON stanno qui.

            model_list:

              # ---------------------------------------------- Alias per RUOLO
              - model_name: orchestrator        # Orchestrator
                litellm_params:
                  model: ollama_chat/gemma4:e4b
                  api_base: http://gateway-host:11434
                model_info:
                  description: Regista del workflow.

              - model_name: reference
                litellm_params:
                  model: gemini/gemini-3-pro-image
                  api_key: os.environ/GEMINI_API_KEY

            litellm_settings:
              drop_params: true          # ignora i parametri non supportati
              request_timeout: 600

            general_settings:
              master_key: os.environ/LITELLM_MASTER_KEY
            """;

    @TempDir
    Path folder;

    private Path file;
    private LiteLlmConnector connector;

    @BeforeEach
    void aGatewayFile() throws IOException {
        file = folder.resolve("litellm_config.yaml");
        Files.writeString(file, ORIGINAL, StandardCharsets.UTF_8);
        connector = new LiteLlmConnector(file.toString(), "http://host.docker.internal:11434", folder.toString());
    }

    private static AgentProfile agent(String name, String model, String description) {
        Agent agent = new Agent(name, "Sviluppatore", "React", model);
        agent.configureBinding(description, null, model, "engine");
        return AgentProfile.of(agent, List.of());
    }

    @Test
    void anAliasIsAddedInAMarkedBlockAtTheEndOfModelListAndNothingElseMoves() throws IOException {
        AgentSyncConnector.Published published = connector.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "Scrive la console."), null);

        String text = Files.readString(file);
        assertThat(published.externalId()).isEqualTo("aicos-frontend-developer");
        assertThat(published.changed()).isTrue();
        // What stood before the block and after it is the original, to the character.
        int begin = text.indexOf("  " + LiteLlmConnector.BEGIN);
        int end = text.indexOf(LiteLlmConnector.END) + LiteLlmConnector.END.length();
        assertThat(begin).isPositive();
        assertThat(text.substring(0, begin).stripTrailing())
                .isEqualTo(ORIGINAL.substring(0, ORIGINAL.indexOf("litellm_settings:")).stripTrailing());
        assertThat(text.substring(end).stripLeading())
                .isEqualTo(ORIGINAL.substring(ORIGINAL.indexOf("litellm_settings:")));
        // The alias points at the agent's model, where the gateway's own entries reach Ollama.
        assertThat(text.substring(begin, end)).contains("- model_name: aicos-frontend-developer")
                .contains("model: ollama_chat/qwen3.5:9b")
                .contains("api_base: http://gateway-host:11434")
                .contains("description: \"Scrive la console.\"");
        // As LiteLLM reads it: the two models that were there, and the alias.
        assertThat(LiteLlmConnector.modelNames(text)).containsExactly("orchestrator", "reference", "aicos-frontend-developer");
        assertThat(published.notes()).anyMatch(note -> note.contains("riavvia"));
    }

    @Test
    void theFileIsCopiedBesideItselfBeforeTheFirstBlockAndOnlyThen() throws IOException {
        connector.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "Scrive la console."), null);
        connector.publish(agent("Database Specialist", "ollama:qwen3.5:4b", "Modella i dati."), null);

        try (Stream<Path> files = Files.list(folder)) {
            List<Path> backups = files.filter(p -> p.getFileName().toString().contains(".aicos-backup-")).toList();
            assertThat(backups).hasSize(1);
            assertThat(Files.readString(backups.getFirst())).isEqualTo(ORIGINAL);
        }
        assertThat(LiteLlmConnector.modelNames(Files.readString(file)))
                .containsExactly("orchestrator", "reference", "aicos-frontend-developer", "aicos-database-specialist");
    }

    @Test
    void publishingTheSameAgentAgainWritesNothing() throws IOException {
        AgentProfile agent = agent("Frontend Developer", "ollama:qwen3.5:9b", "Scrive la console.");
        connector.publish(agent, null);
        String once = Files.readString(file);
        long written = Files.getLastModifiedTime(file).toMillis();

        AgentSyncConnector.Published again = connector.publish(agent, "aicos-frontend-developer");

        assertThat(again.changed()).isFalse();
        assertThat(again.notes()).isEmpty();
        assertThat(Files.readString(file)).isEqualTo(once);
        assertThat(Files.getLastModifiedTime(file).toMillis()).isEqualTo(written);
    }

    @Test
    void aChangedModelRewritesTheAliasInPlace() throws IOException {
        connector.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "Scrive la console."), null);

        connector.publish(agent("Frontend Developer", "ollama:qwen3.5:4b", "Scrive la console."), "aicos-frontend-developer");

        String text = Files.readString(file);
        assertThat(text).contains("model: ollama_chat/qwen3.5:4b").doesNotContain("ollama_chat/qwen3.5:9b");
        assertThat(LiteLlmConnector.modelNames(text)).containsExactly("orchestrator", "reference", "aicos-frontend-developer");
    }

    @Test
    void removingTheLastAliasGivesBackTheOriginalFile() throws IOException {
        connector.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "Scrive la console."), null);

        connector.remove("aicos-frontend-developer");

        assertThat(Files.readString(file)).isEqualTo(ORIGINAL);
    }

    @Test
    void windowsLineEndingsStayWindowsLineEndings() throws IOException {
        Files.writeString(file, ORIGINAL.replace("\n", "\r\n"), StandardCharsets.UTF_8);

        connector.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "Dice \"ciao\" e usa C:\\percorsi."), null);

        String text = Files.readString(file);
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");
        // A quote and a backslash in the description come back as they went in.
        assertThat(connector.list()).extracting(AgentSyncConnector.ExternalAgent::description)
                .containsExactly("Dice \"ciao\" e usa C:\\percorsi.");
    }

    @Test
    void aModelOllamaDoesNotServeIsNotAnAlias() throws IOException {
        AgentProfile claude = agent("Architect", "claude-subscription:default", "Progetta.");

        assertThat(connector.notApplicable(claude)).get().asString().contains("non è un modello locale di Ollama");
        assertThatThrownBy(() -> connector.publish(claude, null)).isInstanceOf(SyncProblemException.class);
        assertThat(connector.notApplicable(agent("Senza modello", null, "x"))).isPresent();
        assertThat(Files.readString(file)).isEqualTo(ORIGINAL);
    }

    @Test
    void aNameTheOperatorAlreadyUsesIsRefusedAndTheFileIsLeftAlone() throws IOException {
        String taken = ORIGINAL.replace("- model_name: reference", "- model_name: aicos-frontend-developer");
        Files.writeString(file, taken, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> connector.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "Scrive la console."), null))
                .isInstanceOf(SyncProblemException.class).hasMessageContaining("due modelli di nome");

        assertThat(Files.readString(file)).isEqualTo(taken);
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.filter(p -> p.getFileName().toString().contains(".aicos-backup-"))).isEmpty();
        }
    }

    @Test
    void aFileThatIsNotYamlIsRefusedBeforeAnythingIsWritten() throws IOException {
        String broken = ORIGINAL.replace("model_list:", "model_list: [unclosed");
        Files.writeString(file, broken, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> connector.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "x"), null))
                .isInstanceOf(SyncProblemException.class);

        assertThat(Files.readString(file)).isEqualTo(broken);
    }

    @Test
    void withoutAFileTheToolSaysWhatIsMissingAndListsNothing() {
        LiteLlmConnector nowhere = new LiteLlmConnector("", "http://host.docker.internal:11434", folder.resolve("empty-home").toString());
        LiteLlmConnector wrong = new LiteLlmConnector(folder.resolve("missing.yaml").toString(), "x", folder.toString());

        assertThat(nowhere.status().availability()).isEqualTo(AgentSyncConnector.Availability.NOT_INSTALLED);
        assertThat(wrong.status().availability()).isEqualTo(AgentSyncConnector.Availability.NOT_CONFIGURED);
        assertThat(nowhere.list()).isEmpty();
        assertThatThrownBy(() -> wrong.publish(agent("Frontend Developer", "ollama:qwen3.5:9b", "x"), null))
                .isInstanceOf(SyncProblemException.class);
    }

    @Test
    void theGatewayOf3dOmniverseIsFoundWithoutBeingConfigured() throws IOException {
        Path config = Files.createDirectories(folder.resolve("home/3D Omniverse/config")).resolve("litellm_config.yaml");
        Files.writeString(config, ORIGINAL, StandardCharsets.UTF_8);

        LiteLlmConnector found = new LiteLlmConnector("", "http://host.docker.internal:11434", folder.resolve("home").toString());

        assertThat(found.status().available()).isTrue();
        assertThat(found.status().detail()).contains(config.toString());
    }
}
