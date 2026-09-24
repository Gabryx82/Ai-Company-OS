# {{PROJECT_NAME}} — governance per gli agenti

> Generato da AI Company OS (progetto #{{PROJECT_ID}}). Questo file è letto da qualunque agente
> aperto in questa cartella: Claude Code (tramite `CLAUDE.md`), Codex, OpenCode, Antigravity.

## Regola principale

**Il contesto vive nei file.** Un prompt ti dice *che cosa* fare («Esegui TASK-007»); i file ti
dicono *come*. Leggi solo ciò che serve, nell'ordine sotto, e non chiedere di incollare nella chat
ciò che puoi leggere da qui.

## Dove sta cosa

| Cerchi | Leggi |
|---|---|
| Perché esiste il progetto, obiettivi, vincoli | `MASTER_PROMPT.md` |
| Il piano complessivo e le fasi | `docs/IMPLEMENTATION_PLAN.md` |
| La fase corrente: scope, strategia, criteri | `docs/phases/PHASE_N.md` |
| La tua task: obiettivo, file, test, criteri di fine | `tasks/TASK-NNN.md` |
| Decisioni architetturali | `docs/adr/` |
| Immagini, mockup, design target | `references/` |
| Mappa completa del contesto | `.aicos/CONTEXT_MAP.md` |
| Profilo del progetto (tipo, stack, autonomia) | `.aicos/project.json` |

## Come si lavora su una task

1. Leggi `tasks/TASK-NNN.md`, poi la sua fase, poi solo i file che la task elenca.
2. Resta nello scope della task. Quello che noti e non fai, scrivilo nella sezione *Note* della task.
3. Scrivi i test richiesti dalla task e falli girare.
4. Alla fine aggiorna la sezione *Esito* della task: cosa hai fatto, file toccati, test, cosa resta.
5. Non dichiarare finita una task: la chiude l'operatore dopo la review (Human-in-the-Loop).

## Livello di autonomia: {{AUTONOMY}}

{{AUTONOMY_RULES}}

## Stack

{{STACK}}
