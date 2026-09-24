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
