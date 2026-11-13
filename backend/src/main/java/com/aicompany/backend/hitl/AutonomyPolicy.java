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
            // PHASE 37 (ADR-044, revises ADR-022 §3-4): the code is the agent's; the operator puts it in the
            // files, one explained step at a time, and is asked to predict, to complete, to explain.
            case GUIDED -> """
                    - L'operatore sta **imparando facendo**: il codice lo scrivi tu, nei file lo porta lui.
                    - **Non modificare i file del progetto.** Scrivi una lezione in `lessons/<TASK>.lezione.md`: per ogni \
                    passo il file, il codice completo e la spiegazione **riga per riga**, in parole semplici. Il formato \
                    è nel pacchetto di consegna della task.
                    - Prima del codice fai **prevedere** qualcosa all'operatore; alla fine metti gli esercizi (quiz, \
                    completa, trova l'errore, spiega a parole tue): si impara facendo, non leggendo.
                    - Quando l'operatore ha copiato un passo, **verifica** quello che ha scritto; di un errore spiega la \
                    causa prima della correzione.
                    - Se l'operatore dice che non può seguire, lavora in autonomia e lascia la lezione di ripasso.""";
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

    /**
     * PHASE 37 (ADR-044): the rules of one handoff. The project's level says how the operator
     * works as a rule; the operator may say, for this task, that they cannot follow -- and then
     * the agent works by itself whatever the level, and leaves the lesson to go through after.
     */
    public static String rules(AutonomyLevel level, LearningMode mode) {
        if (mode == LearningMode.RECAP && level == AutonomyLevel.GUIDED) {
            return """
                    - Per questa task l'operatore **non può seguire**: implementala, provala e documentala in autonomia, \
                    dentro il suo scope, senza aspettare risposte.
                    - Registra nella task le decisioni prese e i debiti trovati.
                    - Lascia la **lezione di ripasso** descritta sotto: l'operatore sta imparando, e la percorre dopo.
                    - Fermati comunque prima di ciò che non si può annullare.""";
        }
        return rules(level);
    }

    /** The same rules, addressed to a model inside a run's system prompt. */
    public static String systemInstructions(AutonomyLevel level) {
        return "Human-in-the-Loop level of this project: " + level.name() + ".\n" + switch (level) {
            case GUIDED -> """
                    The operator is learning by doing. You write the code; the operator copies it into the \
                    files. Explain the idea in simple words first, ask the operator to predict one outcome, then \
                    give the code in small steps, each with its file and a line-by-line explanation in plain \
                    language; end with exercises (a quiz, a blank to fill, a question to answer in their own \
                    words). Explain the cause of an error before its fix. Answer in Italian.""";
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
