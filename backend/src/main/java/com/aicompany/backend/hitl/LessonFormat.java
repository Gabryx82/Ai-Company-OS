package com.aicompany.backend.hitl;

/**
 * PHASE 37 (ADR-044): the shape of a lesson file, as the agents are told it and as
 * {@code LessonParser} reads it. One place for both, so that what an agent is asked
 * to write and what the console can bring to life never drift apart.
 */
public final class LessonFormat {

    private LessonFormat() {
    }

    /** The folder of a project's lessons. */
    public static final String FOLDER = "lessons";

    /** {@code lessons/TASK-012.lezione.md} for a project's task; {@code TASK.lezione.md} for a task without a project. */
    public static String pathFor(String taskLabel, boolean inProject) {
        return inProject ? FOLDER + "/" + taskLabel + ".lezione.md" : "TASK.lezione.md";
    }

    public static boolean isLesson(String fileName) {
        return fileName.endsWith(".lezione.md");
    }

    /** What the agent does in each mode, as a handoff package says it. Empty for {@link LearningMode#NONE}. */
    public static String instructions(LearningMode mode, String lessonPath) {
        return switch (mode) {
            case TEACH -> """
                    L'operatore **impara facendo**. Il codice lo scrivi tu; nei file lo porta lui.

                    - **Non modificare i file del progetto.** Scrivi la lezione in `%s`, nel formato qui sotto: è l'unico \
                    file che crei, oltre all'esito.
                    - In ogni passo metti il codice **completo** di quel passo, il file in cui va e la spiegazione \
                    **riga per riga**, in parole semplici: che cosa fa quella riga e perché serve, senza gergo non spiegato.
                    - Prima del primo passo fai **prevedere** qualcosa all'operatore; dopo l'ultimo metti gli esercizi. \
                    Almeno un quiz, un «completa» e uno «spiega a parole tue»: l'apprendimento non è lettura.
                    - Passi piccoli: uno alla volta si deve poter copiare, provare e capire. Se la task è grande, \
                    la lezione ha più passi, non passi più lunghi.
                    - Quando l'operatore ha copiato un passo e ti chiede di controllare, guarda quello che ha scritto nel \
                    file e spiega la **causa** di ogni errore prima della correzione.
                    """.formatted(lessonPath) + "\n" + GUIDE;
            case RECAP -> """
                    L'operatore **non può seguirti adesso**: lavora in autonomia. Implementa, prova e documenta la task \
                    dentro il suo scope, senza fermarti ad aspettare risposte.

                    - A lavoro finito scrivi in `%s` la **lezione di ripasso**, nel formato qui sotto: l'operatore la \
                    percorre dopo, per capire quello che hai costruito.
                    - I passi ripercorrono il codice che hai **già scritto**, nell'ordine in cui conviene leggerlo: indica \
                    il file con `(già scritto)` e spiega riga per riga, in parole semplici, le parti che contano.
                    - La previsione e gli esercizi ci sono lo stesso: senza di essi è un riassunto, non una lezione.
                    """.formatted(lessonPath) + "\n" + GUIDE;
            case NONE -> "";
        };
    }

    /** The format itself. Every heading and every bold label below is read by the console: keep them as they are. */
    public static final String GUIDE = """
            ### Formato della lezione

            ````markdown
            # Lezione — <titolo: che cosa si costruisce>

            ## In parole semplici

            <Tre o quattro frasi senza gergo: che cosa stiamo costruendo, a che cosa serve, a che cosa somiglia \
            nella vita di tutti i giorni.>

            ## Prova a prevedere

            <Una domanda a cui rispondere PRIMA di vedere il codice.>

            **Risposta**: <la risposta, con il perché.>

            ## Passo 1 — <titolo del passo>

            **File**: `percorso/del/file.ext` (nuovo)

            ```linguaggio
            <il codice completo di questo passo>
            ```

            **Riga per riga**

            - `<frammento di una riga>` — <che cosa fa e perché serve, in parole semplici>
            - `<frammento>` — <...>

            **Perché**: <la scelta dietro questo passo: perché così e non in un altro modo.>

            ## Passo 2 — <titolo del passo>

            **File**: `percorso/di/un/altro/file.ext` (modifica)

            ```linguaggio
            <il pezzo che cambia, com'è dopo la modifica>
            ```

            **Riga per riga**

            - `<frammento>` — <...>

            ## Mettiti alla prova

            ### Quiz — <domanda>

            - [ ] <opzione sbagliata>
            - [x] <opzione giusta>
            - [ ] <opzione sbagliata>

            **Spiegazione**: <perché quella è giusta.>

            ### Completa — <che cosa manca>

            ```linguaggio
            <codice con ___ al posto di ciò che l'operatore deve scrivere>
            ```

            **Soluzione**: `<ciò che va al posto di ___>`

            **Spiegazione**: <...>

            ### Trova l'errore — <che cosa non funziona>

            ```linguaggio
            <codice con un errore>
            ```

            **Soluzione**: riga 2

            **Spiegazione**: <la causa dell'errore, poi la correzione. Sopra, al posto di 2, il numero della riga sbagliata.>

            ### Spiega a parole tue — <domanda aperta>

            **Punti chiave**:

            - <un punto che una buona risposta tocca>
            - <un altro>

            ## Cosa hai imparato

            - **<Concetto>**: <una frase che lo dice, da ripassare fra qualche giorno>
            ````

            Dopo `**File**:` il percorso è relativo alla cartella del progetto; fra parentesi `nuovo`, `modifica` \
            oppure `già scritto`. In un passo di modifica il codice è il pezzo che cambia, com'è dopo la modifica.
            """;
}
