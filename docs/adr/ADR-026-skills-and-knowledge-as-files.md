# ADR-026 — Skill e knowledge come file

- **Stato**: Accettata e implementata (PHASE 19)
- **Data**: 2026-09-25
- **Decisa da**: agente, su direttiva umana del 2026-09-25 (§3 «Skills modificabili manualmente»)
- **Estende**: ADR-023 (harness)

## 1. Decisione

Ogni **skill** e ogni voce di **knowledge** è un file Markdown con un frontmatter:

```
<libreria>/skills/<chiave>/SKILL.md
<libreria>/knowledge/<chiave>.md
```

```markdown
---
key: revisione-api
name: Revisione delle API REST
kind: SKILL
description: Controlla verbi, codici di stato e problem detail.
tags: [api, rest]
source: https://… (se importata)
---

# Istruzioni in Markdown…
```

La libreria sta in `%USERPROFILE%\.aicos\library` (`aicos.library.root` la sposta, ad esempio
dentro un repository git per versionarla). La forma è quella dei `SKILL.md` di Claude Code: una
skill scritta qui si legge naturalmente anche lì.

## 2. Il file è la verità, il database l'indice

`harness_resources` resta l'indice (chiave, tipo, nome, descrizione, tag) con `origin` (`CATALOG`,
`FILE`, `WEB`, `USER`) e `file_path` (V22). Quando file e indice divergono vince il file:

- **dalla console**: `PUT /api/resources/{key}/file` valida il frontmatter, scrive il file e
  aggiorna l'indice sotto il tag della voce (ADR-009); chiave e tipo non si cambiano dall'interno del
  file;
- **a mano, nell'editor**: la console legge sempre il file dal disco; «Rileggi cartella»
  (`POST /api/library/sync`) indicizza i file nuovi (origine `FILE`) e aggiorna quelli noti, e
  **segnala** i file non validi invece di indovinarli. Niente viene cancellato;
- **dal catalogo**: una skill di catalogo non ha file finché non lo si crea
  (`POST /api/resources/{key}/file`), a partire da ciò che l'indice sa; un file esistente non viene
  mai sovrascritto.

«Apri in VS Code» apre la cartella della voce; il percorso si calcola dalla chiave validata, mai
dalla richiesta. Framework, MCP e tool restano voci di catalogo con la loro configurazione testuale.

## 3. Dove arrivano le skill

- **Run nell'AI Engine**: il prompt di sistema dell'agente riceve il corpo di ogni skill e knowledge
  associata (fino a 4000 caratteri ciascuna) dopo l'elenco dell'harness.
- **Handoff**: il pacchetto elenca il percorso del file di ogni skill, perché Claude Code, Codex o
  l'IDE lo leggano; il prompt completo le include.

## 4. Import dal web

`POST /api/library/import` con un URL. Solo `https://`, host pubblici: rifiutati loopback, indirizzi
privati, link-local, multicast e ULA IPv6 (difesa da SSRF); nessun redirect seguito; al massimo
256 KB; una pagina HTML è rifiutata. Un link `github.com/…/blob/…` diventa il file raw. Un Markdown
senza frontmatter ne riceve uno (chiave dal nome del file, titolo dal primo `#`, `source` = URL). La
skill importata è un file come le altre, modificabile.

## 5. Test

`LibraryApiTest` (file creato da una voce di catalogo, modifica a mano e dalla console con If-Match,
chiave immutabile, nuova skill, conflitto di chiave, voce MCP senza file, rilettura della cartella
con file non valido segnalato, import con riscrittura GitHub e rifiuti SSRF, skill nel prompt di una
run), `SkillDocument` coperto attraverso l'API; console: `KnowledgeHub.test` (apertura, modifica e
salvataggio con il tag).

## Revisione PHASE 34 (2026-11-07) — le risorse adottate dal progetto arrivano all'agente

Rivede il §3. Fino a qui le risorse adottate da un progetto (`project_resources`, «Associa» del Knowledge Hub)
erano solo elencate nella pagina del progetto: né il pacchetto di handoff né le run le leggevano. Ora valgono per
ogni agente che lavora al progetto, **dopo le sue e senza ripetere** quelle che l'agente ha già:

- **Handoff**: il pacchetto aggiunge alla sezione delle skill il blocco «Adottate dal progetto», con il percorso del
  file di ogni skill e knowledge; gli MCP adottati finiscono in `.mcp.json` / `opencode.json` come quelli
  dell'agente (ADR-025, TD-48); il prompt completo li include.
- **Run nell'AI Engine**: il prompt di sistema aggiunge «Adopted by this project, for every agent working on it: …»
  e il corpo di ogni skill e knowledge adottata, fino a 4000 caratteri ciascuna. Un progetto che non ha adottato
  nulla di nuovo lascia il prompt com'era (la regressione di PHASE 6 resta).

La pagina del progetto lo dice sopra l'elenco delle risorse adottate. Test: `ProjectResourcesApiTest` (pacchetto,
`.mcp.json`, prompt completo, prompt di sistema di una run, la skill dell'agente adottata anche dal progetto una sola
volta).

## Revisione PHASE 35 (2026-11-08) — skill pertinenti, caricate quando servono (ADR-041 §7–§8)

Rivede il §3 e la revisione PHASE 34 per ciò che arriva all'agente; il file resta la verità (§2).

- **Run nell'AI Engine**: non più il corpo di ogni skill e knowledge (fino a 4000 caratteri ciascuna), ma **al più due**,
  quelle pertinenti alla task per parole chiave (chiave, nome e tag pesano 2, descrizione 1, soglia 2; `Pertinence`), e
  di ciascuna la sezione **`## Essenziale`** se c'è, altrimenti il corpo fino al primo titolo di secondo livello, entro
  `aicos.runs.skill-essential-chars` (1500), tagliata a un paragrafo. Il prompt di sistema nomina sempre l'intero
  harness e ciò che il progetto ha adottato, nella parte stabile che precede quella che dipende dalla task.
- **Handoff a uno strumento che carica le skill da sé** (Claude Code CLI e app desktop, Codex, OpenCode, Antigravity,
  Verdent): il pacchetto non chiede più di leggere i file della libreria; nomina ogni skill con la sua descrizione e
  dice «usa la skill `<nome>` se pertinente», dopo aver verificato col Knowledge Hub che la copia sia nella cartella
  personale dello strumento. Se manca, è indietro o la cartella non è del Hub, lo dice, propone la copia dal Hub e dà
  il file da leggere intanto, se pertinente; non scrive nulla. Le knowledge restano indicate col percorso, solo se
  pertinenti. Agli altri strumenti il pacchetto arriva come prima.

Per una skill della libreria, scrivere una sezione `## Essenziale` è il modo di decidere che cosa ne riceve un modello
locale. Test: `PertinenceTest`, `PertinentSkillsInRunsApiTest`, `HandoffSkillsApiTest`.

## Revisione PHASE 36 (2026-11-08) — nessuna voce sparisce in silenzio (ADR-042)

- **Una voce senza file, o con un file non valido**, prima spariva: il pacchetto la nominava senza istruzioni e nessuno
  lo vedeva. `SkillLibrary.check` dà lo stato del file (`OK`, `MISSING`, `INVALID`: frontmatter assente o illeggibile,
  chiave o kind diversi dalla voce); il pacchetto lo scrive sotto la voce con «⚠», la verifica prima della consegna lo
  mostra come avviso (non bloccante: l'agente riceve comunque nome e descrizione).
- **La knowledge non pertinente** (rivede la revisione PHASE 35): agli strumenti con skill native non è più tolta dal
  pacchetto, è nominata con «consultala solo se la task la richiede» e il percorso; nelle misure di ADR-041 è una
  lettura facoltativa, quindi le letture obbligatorie non crescono.
- **La copia del Knowledge Hub nella cartella dello strumento** si vede prima di aprirlo: la verifica dice per ogni
  skill se la copia è allineata, modificata nell'app, mancante, indietro, estranea o in conflitto, e per le ultime
  quattro lo segnala come avviso; per una mancante o indietro il pannello offre «Copia nello strumento», che usa la copia
  del Hub (`POST /api/skill-hub/library/{key}/copies/{app}`, mai con sostituzione).

Test: `TaskReadinessApiTest`, `HandoffSkillsApiTest`, `HarnessChainApiTest`.
