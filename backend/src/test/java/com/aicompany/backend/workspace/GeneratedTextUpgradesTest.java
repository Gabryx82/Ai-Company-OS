package com.aicompany.backend.workspace;

import com.aicompany.backend.workspace.service.GeneratedTextUpgrades;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 36 (ADR-042 §5): a passage AI Company OS wrote, still verbatim, follows the way
 * tasks are delivered now; a passage the operator changed, and everything else in the
 * file, stays as it is.
 */
class GeneratedTextUpgradesTest {

    /** AGENTS.md as the template wrote it until PHASE 33, as interfaccia-movieseseries still has it. */
    private static final String AGENTS_V1 = """
            # Rubrica — governance per gli agenti

            ## Come si lavora su una task

            1. Leggi `tasks/TASK-NNN.md`, poi la sua fase, poi solo i file che la task elenca.
            2. Resta nello scope della task. Quello che noti e non fai, scrivilo nella sezione *Note* della task.
            3. Scrivi i test richiesti dalla task e falli girare.
            4. Alla fine aggiorna la sezione *Esito* della task: cosa hai fatto, file toccati, test, cosa resta.
            5. Non dichiarare finita una task: la chiude l'operatore dopo la review (Human-in-the-Loop).

            ## Livello di autonomia: GUIDED

            Regole dell'operatore, scritte a mano.
            """;

    private static final String TASK_V1 = """
            # TASK-001 — Schema dei contatti

            ## Obiettivo

            Le tabelle dei contatti.

            ## Prompt compatto

            > Esegui TASK-001 seguendo `tasks/TASK-001.md` e la governance in `AGENTS.md`.

            ## Esito

            Fatto: vedi [TASK-001.esito.md](TASK-001.esito.md).

            ## Note

            Una nota dell'agente.
            """;

    @Test
    void theStepsOfAnEarlierAgentsFileStartFromThePackageAndTheRestStays() {
        String upgraded = GeneratedTextUpgrades.upgrade("AGENTS.md", AGENTS_V1).orElseThrow();
        assertThat(upgraded).contains("1. Parti dal pacchetto della tua task in `.aicos/handoffs/`")
                .contains("chiedi all'operatore\n   di consegnare la task")
                .doesNotContain("1. Leggi `tasks/TASK-NNN.md`, poi la sua fase")
                .startsWith("# Rubrica — governance per gli agenti\n")
                .endsWith("## Livello di autonomia: GUIDED\n\nRegole dell'operatore, scritte a mano.\n");
        assertThat(GeneratedTextUpgrades.upgrade("AGENTS.md", upgraded)).as("a second pass changes nothing").isEmpty();
    }

    @Test
    void stepsTheOperatorChangedAreNotTouched() {
        String edited = AGENTS_V1.replace("falli girare", "falli girare due volte");
        assertThat(GeneratedTextUpgrades.upgrade("AGENTS.md", edited)).isEmpty();
    }

    @Test
    void theAgentsFileOfTheTemplateIsAlreadyCurrent() throws Exception {
        String template = new ClassPathResource("workspace-templates/AGENTS.md").getContentAsString(StandardCharsets.UTF_8);
        assertThat(GeneratedTextUpgrades.upgrade("AGENTS.md", template)).isEmpty();
        // PHASE 34's first step becomes the current one.
        String phase34 = template.replace(template.substring(template.indexOf("1. Parti dal pacchetto"),
                template.indexOf("2. Leggi")), """
                1. Se in `.aicos/handoffs/` c'è il pacchetto della tua task (`TASK-NNN-<strumento>.md`), leggilo per primo:
                   contiene il ruolo, le skill, le knowledge e le regole che AI Company OS ha preparato per te.
                """);
        assertThat(GeneratedTextUpgrades.upgrade("AGENTS.md", phase34)).hasValue(template);
    }

    @Test
    void theOldPromptOfATaskDocumentBecomesTheDeliveryThatLeadsToThePackage() {
        String upgraded = GeneratedTextUpgrades.upgrade("tasks/TASK-001.md", TASK_V1).orElseThrow();
        assertThat(upgraded).doesNotContain("## Prompt compatto")
                .contains("## Come si consegna\n\nDalla console di AI Company OS")
                .contains("> Esegui TASK-001 partendo dal suo pacchetto di consegna, il più recente "
                        + "`.aicos/handoffs/TASK-001-*.md`")
                .contains("## Obiettivo\n\nLe tabelle dei contatti.\n\n")
                .contains("## Esito\n\nFatto: vedi [TASK-001.esito.md](TASK-001.esito.md).\n\n## Note\n\nUna nota dell'agente.\n");
        assertThat(GeneratedTextUpgrades.upgrade("tasks/TASK-001.md", upgraded)).isEmpty();
    }

    @Test
    void windowsLineEndingsAreKept() {
        String crlf = TASK_V1.replace("\n", "\r\n");
        String upgraded = GeneratedTextUpgrades.upgrade("tasks/TASK-001.md", crlf).orElseThrow();
        assertThat(upgraded).contains("## Come si consegna\r\n\r\n").doesNotContain("## Prompt compatto");
        assertThat(upgraded.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void theOldPromptOfTheContextMapBecomesBoth() {
        String old = "# Mappa\n\nPrompt tipo, che basta a un agente per partire:\n\n"
                + "> Esegui TASK-NNN seguendo `tasks/TASK-NNN.md` e la governance in `AGENTS.md`.\n\n| una | tabella |\n";
        String upgraded = GeneratedTextUpgrades.upgrade(".aicos/CONTEXT_MAP.md", old).orElseThrow();
        assertThat(upgraded).contains("> Leggi .aicos/handoffs/TASK-NNN-<strumento>.md ed esegui la task che descrive.")
                .contains("> Esegui TASK-NNN partendo dal suo pacchetto di consegna")
                .doesNotContain("seguendo `tasks/TASK-NNN.md`")
                .endsWith("| una | tabella |\n");
    }

    @Test
    void onlyTheFilesItKnowsAreConsidered() {
        assertThat(GeneratedTextUpgrades.upgrade("tasks/TASK-001.esito.md", TASK_V1)).isEmpty();
        assertThat(GeneratedTextUpgrades.upgrade("docs/TASK-001.md", TASK_V1)).isEmpty();
        assertThat(GeneratedTextUpgrades.upgrade("tasks/old/TASK-001.md", TASK_V1)).isEmpty();
        assertThat(GeneratedTextUpgrades.upgrade("CLAUDE.md", AGENTS_V1)).isEmpty();
    }
}
