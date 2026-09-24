package com.aicompany.backend.hitl;

import com.aicompany.backend.project.model.AutonomyLevel;

/**
 * Progressive Human-in-the-Loop and Learning with Agent (ADR-022), as the words
 * agents actually receive: in a project's {@code AGENTS.md}, in the system prompt
 * of an engine run, in a handoff to an external agent.
 *
 * <p>One place, so that what an external agent reads in the workspace and what an
 * engine run is told can never disagree about how much the operator delegates.
 */
public final class AutonomyPolicy {

    private AutonomyPolicy() {
    }

    public static String label(AutonomyLevel level) {
        return switch (level) {
            case GUIDED -> "GUIDED — imparo facendo";
            case SUPERVISED -> "SUPERVISED — supervisiono e integro";
            case DELEGATED -> "DELEGATED — delego e revisiono";
            case FINAL_REVIEW -> "FINAL_REVIEW — solo review finale";
        };
    }

    /** The rules as a Markdown list, for AGENTS.md and handoffs. */
    public static String rules(AutonomyLevel level) {
        return switch (level) {
            case GUIDED -> """
                    - L'operatore sta **imparando**: il lavoro lo fa soprattutto lui, tu lo guidi.
                    - Prima di scrivere codice spiega l'approccio in poche righe e chiedi all'operatore di \
                    **prevedere** il risultato o di rispondere a una domanda.
                    - Lascia all'operatore i pezzi piccoli e significativi da scrivere (indicali come \
                    `// TODO(operatore): …`) e poi **verifica** quello che ha scritto.
                    - Quando c'è un errore, spiegane la causa prima della correzione.
                    - Chiudi con 2–3 domande di verifica della comprensione.""";
            case SUPERVISED -> """
                    - L'operatore **supervisiona**: proponi lavoro completo, ma motivando ogni scelta non ovvia.
                    - Separa il codice da integrare dalle spiegazioni, così che l'operatore possa copiarlo e \
                    rivederlo.
                    - Fermati e chiedi prima di decisioni architetturali o irreversibili.""";
            case DELEGATED -> """
                    - Implementa, testa e documenta la task in autonomia, dentro il suo scope.
                    - Registra nella task le decisioni prese e i debiti trovati.
                    - L'operatore revisiona e approva: rendi la review facile (riassunto, file toccati, test).""";
            case FINAL_REVIEW -> """
                    - Coordina il lavoro in autonomia fino al risultato verificabile.
                    - L'operatore fa solo la **review finale** funzionale e visiva: prepara istruzioni precise \
                    per provarlo e l'elenco di ciò che non è stato verificato.""";
        };
    }

    /** The same rules, addressed to a model inside a run's system prompt. */
    public static String systemInstructions(AutonomyLevel level) {
        return "Human-in-the-Loop level of this project: " + level.name() + ".\n" + switch (level) {
            case GUIDED -> """
                    The operator is learning. Teach while working: explain the approach briefly, ask the \
                    operator to predict an outcome or answer one question, leave one or two small meaningful \
                    pieces for the operator to write (mark them TODO(operatore)), explain causes of errors, and \
                    end with two or three short questions that check understanding. Answer in Italian.""";
            case SUPERVISED -> """
                    The operator supervises and integrates your work. Give complete work, justify every \
                    non-obvious choice, keep code separate from explanations, and stop to ask before \
                    architectural or irreversible decisions. Answer in Italian.""";
            case DELEGATED -> """
                    You implement, test and document within the task's scope. Record decisions and debts. \
                    Make the operator's review easy: summary, files touched, tests. Answer in Italian.""";
            case FINAL_REVIEW -> """
                    Work autonomously to a verifiable result. The operator performs only the final functional \
                    and visual review: give precise instructions to try it and list what was not verified. \
                    Answer in Italian.""";
        };
    }
}
