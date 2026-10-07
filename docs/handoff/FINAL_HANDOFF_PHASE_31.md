# Final handoff — PHASE 31: il Knowledge Hub come punto unico

Branch `autonomous/phase-31-knowledge-hub` (dalla punta di PHASE 30, `4127377`), **non pushato**. `master`
intoccato. Decisioni e ragioni: [ADR-036](../adr/ADR-036-knowledge-hub-across-apps.md).

## Correzione dopo la tua verifica (2026-10-07, pomeriggio)

> «Per quanto riguarda Claude non vedo i plugin che erano già installati.»

| Causa | Correzione | Verifica |
|---|---|---|
| **Claude Desktop è l'app del Microsoft Store**: Windows sposta la sua cartella dati in `%LOCALAPPDATA%\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Roaming\Claude`. La vedono nel percorso normale solo i processi lanciati dall'app Claude — come la sessione che ha fatto lo smoke (228 voci). Il tuo backend, avviato da PowerShell, guardava `%APPDATA%\Claude` e trovava **zero** voci (l'ho letto dalla sua sincronizzazione nel tuo DB, in sola lettura) | il Hub cerca anche nella cartella del pacchetto e usa quella aggiornata per ultima | scanner con `%APPDATA%` volutamente vuoto: trova tutto nella cartella del pacchetto; test con i dati solo lì |
| Il Hub elencava **solo le skill** | **plugin** come elementi a sé con i componenti (skill, comandi, agenti, hook, connettori) — compare anche **security-guidance**, che ha solo hook; **connettori** (MCP) uniti per nome, con chi li fornisce; **knowledge**: la memoria di Claude Code per progetto. Filtro **Tipo**: Skill · Plugin · Connettori · Knowledge | sul tuo PC: Claude Desktop 13 plugin, 228 skill, 70 connettori + estensione Blender; Claude Code il plugin frontend-design (da `installed_plugins.json` o da `enabledPlugins`), i tuoi 4 server MCP (sketchup, blender, omniverse-toolbox, unreal) + «Claude Docs» dell'account, la memoria di 2 progetti |
| — | dei connettori **mai** argomenti, variabili d'ambiente, header, query string o il file di configurazione (in `~/.claude.json` c'è anche il tuo account) | test con segreti finti in tutti quei punti: non compaiono in nessuna risposta |
| — | ciò che il Hub impara a leggere ora (es. i 228 elementi di Claude Desktop) viene **fotografato**, non registrato in Attività come «installato oggi» | test che parte da un'istantanea della versione precedente |

**Verifica dal vivo** (backend di prova su `aicompany_p31`, con `%APPDATA%` volutamente vuoto come lo vede il tuo
backend): Claude Desktop letto da `Packages\Claude_pzs8sxrjxfjjc\…`, **13 plugin · 228 skill · 70 connettori**;
Claude Code 1 plugin · 5 connettori · 2 memorie; in tutto **467** elementi (334 skill, 40 plugin, 86 connettori,
7 knowledge). La sincronizzazione su un'istantanea della versione precedente: **0 installate, 0 rimosse, 0
modificate** — niente falsi «installato oggi». Suite: Java **573** (0 falliti, 1 saltato preesistente) · console **65**.

**Da fare tu**: il backend che hai avviato alle 15:04 gira con il codice di prima (senza riavvio automatico):
**riavvialo** dal branch, poi *Knowledge Hub → Installate → Claude Desktop* (e *Tipo: Plugin / Connettori*).

## Che cosa vedi

**Knowledge Hub** ha ora un'intestazione con il riepilogo (quante skill e knowledge, in quante app,
quante nella libreria e copiate nelle app, quanti aggiornamenti, l'ultima lettura), i pulsanti
**Sincronizza ora** e **Verifica aggiornamenti**, e cinque schede:

| Scheda | Che cosa |
|---|---|
| **Installate** (si apre qui) | a sinistra le app: AI Company OS, Claude Code, Claude Desktop, Codex · ChatGPT, Agent Skills condivise (`~/.agents`, che Codex legge), Antigravity, Gemini CLI, OpenCode, Verdent, Copilot CLI, Cursor — con i conteggi; quelle non presenti sul PC in fondo. A destra le skill **per app → tipologia** (Libreria, Personali, Plugin uno per uno con versione e marketplace, Di sistema, Knowledge, Istruzioni globali), filtri per tipologia, **categoria** (Documenti, Design, DevOps, Business, Sviluppo, …) e stato (con aggiornamenti, copie del Hub, disattivate nell'app). Ogni scheda dice anche «anche in: Claude Desktop, Verdent» quando la stessa skill è in più app. Scegliendo un'app: le sue cartelle (scrivibile / gestita dall'app) e gli agenti che lavorano lì |
| dettaglio di una skill di un'app | il `SKILL.md` (sola lettura), file, plugin, versione, chi la legge; **Apri cartella**; **Importa nella libreria** con la chiave che scegli |
| dettaglio di una skill della libreria | l'editor di sempre (salva, VS Code, associa ad agenti e progetti, elimina) più **Nelle app**: per ogni app stato della copia e *Installa* / *Aggiorna* / *Sovrascrivi* / *Togli* (admin), e **Scarica .zip** per Claude Desktop |
| **Marketplace** | come il Mockup Hub: una ricerca e lo scaffale dei provider. **skills.sh**, **ClawHub** e i repository ufficiali **Anthropic**, **OpenAI** (per Codex) e **Vercel** si cercano, si sfogliano e si installano da qui, con anteprima di file e `SKILL.md`, avviso sugli script e scelta delle app in cui installare. SkillsMP, claude-plugins.dev, LobeHub, Smithery, la directory dei plugin di Claude e gli altri si aprono nel browser (con la ricerca) |
| **Aggiornamenti** | pacchetti dei marketplace con una versione nuova; skill importate e cambiate nell'app d'origine; copie da aggiornare o cambiate nell'app (da riportare nella libreria); plugin di Claude Desktop e Claude Code con una versione pubblicata più recente (li aggiorna l'app: il Hub la apre o dà il comando). *Aggiorna tutto* applica quelli sicuri; ciò che sovrascrive una modifica chiede conferma e tiene un backup |
| **Attività** | che cosa le sincronizzazioni hanno visto installare, rimuovere o modificare in ogni app |
| **Libreria e catalogo** | la vista di prima, invariata: tutte le voci, Rileggi cartella, Importa da URL, Nuova skill, template di agenti |

La console rilegge le app all'apertura e ogni minuto mentre la pagina è aperta; il backend sincronizza
ogni 5 minuti.

## Che cosa il Hub tocca sul disco

- **Legge** le cartelle di skill delle app (home e `%APPDATA%`), mai altro.
- **Scrive** solo: nella libreria di AI Company OS e nelle cartelle **personali** delle app
  (`~/.claude/skills`, `~/.agents/skills`, `~/.gemini/antigravity/skills`, `~/.config/opencode/skills`,
  `~/.verdent/skills`, `~/.copilot/skills`, …), quando glielo chiedi. Mai nei plugin, nelle skill di
  sistema o nella cartella di Claude Desktop (che riceve le skill dall'account: per quella c'è lo `.zip`).
- **Non sovrascrive** una cartella che non ha scritto lui, né una copia che hai modificato, né una skill
  della libreria che hai cambiato dopo averla installata, senza la tua conferma.
- **Non elimina**: ciò che sostituisce o togli va in `%USERPROFILE%\.aicos\backups\skills\`.

## Numeri

| | |
|---|---|
| Sul tuo PC (letto il 2026-10-07) | **323** skill e knowledge in 7 sedi: 228 Claude Desktop (20 skill dell'account + 13 plugin), 66 Codex (plugin, di sistema, AGENTS.md), 20 Antigravity, 9 Verdent; 1,2 s la prima lettura, 0,4 s le successive |
| Ricerca nei marketplace (dal vivo) | ~1,6 s su skills.sh + ClawHub + tre repository ufficiali; «seo» → 60 risultati |
| Suite | Java **573** (572 verdi + 1 saltato su Windows, preesistente; erano 552) · console **65** (erano 57) · engine invariato |
| Test nuovi | `SkillFilesTest` 8 · `SkillHubApiTest` 12 · `LibraryApiTest` +1 · console `hubModel.test` 4, `SkillHub.test` 4 |
| Mutazioni | **10/10 rosse**, più **7/7** sulle guardie della correzione (ogni guardia tolta fa fallire il suo test) |
| Database | **V30**: `skill_installations`, `skill_snapshot`, `skill_sync_events`. Additiva |
| API | 17 percorsi nuovi, contratto OpenAPI **solo additivo** (verificato confrontandolo con quello di PHASE 30) |

## Corretto per strada

- L'**import da URL** rifiutava un `SKILL.md` standard (frontmatter con `name`, senza `key`): ora la chiave
  viene dal nome e il frontmatter dell'autore resta com'è (licenza, metadati), con la sola riga `source:` in più.
- Una **descrizione su più righe** (`description: >-`) ora si legge intera anche nella libreria.

## Smoke dal vivo (2026-10-07)

Backend del branch su un database **nuovo e separato** (`aicompany_p31`, porta 8091, home e workspace
temporanei, nessun programma avviato), console sulla porta 5180 nel browser integrato, come admin. Le
cartelle delle tue app sono state **lette**, mai scritte: nessuna installazione nelle app durante lo smoke
(`~/.claude/skills` e `~/.agents/skills` non esistono ancora, e non sono state create).

| Passo | Esito |
|---|---|
| Installate | 335 voci (le 323 delle app + 12 della libreria), app e tipologie come sopra; plugin di Codex raggruppati con versione e marketplace |
| Plugin Codex con nome-id (`app-6a32…`) | mostrato come id → **corretto**: ora «Higgsfield», dal manifest |
| Marketplace, ricerca «seo» | 60 risultati, «già in: Claude Desktop» sulle skill che hai già; prima ~15 s → **corretto** (descrizioni lette in parallelo e in cache): ~1,6 s |
| Sfoglia Anthropic | 20 skill con descrizione; «già in: Claude Desktop, Verdent» su pdf, docx, xlsx, pptx, skill-creator |
| Installa «algorithmic-art» (solo libreria) | 4 file identici all'originale, provenienza registrata; la voce compare in Installate con «da Anthropic · skill ufficiali» e la sezione Nelle app |
| Sincronizza ora · Verifica aggiornamenti | «0 installate, 0 rimosse, 0 modificate»; 0 aggiornamenti, nessun errore (i plugin di Claude Desktop sono all'ultima versione pubblicata) |
| Pacchetto .zip | 4 file sotto `algorithmic-art/` |
| 375 px | nessuno scorrimento orizzontale |

Lo smoke usa il database `aicompany_p31`: per toglierlo, `DROP DATABASE aicompany_p31;`. Il tuo DB
`aicompany` e la tua `%USERPROFILE%\.aicos` non sono stati toccati: al primo avvio il DB riceve **V30**.

## Da provare tu

1. Apri **Knowledge Hub → Installate** e scegli *Codex · ChatGPT* o *Claude Desktop*: le tue skill, per tipologia.
2. **Marketplace**: cerca qualcosa, *Anteprima e installa*, spunta **Claude Code** o **Agent Skills condivise** (per Codex).
3. Apri la skill installata nella libreria: **Nelle app** dice dove è. Modificala e salva: in **Aggiornamenti**
   compare *Aggiorna la copia*. Modifica la copia in `~/.claude/skills/...` e premi *Sincronizza ora*: compare
   *Riporta nella libreria*.
4. **Attività** dopo aver installato o tolto una skill da un'app (anche fuori da AI Company OS).

## Debiti nuovi

TD-74 … TD-82 (`docs/DEBT_REGISTRY.md`): plugin di Codex senza verifica (catalogo locale inaffidabile);
plugin aggiornati dalle loro app, non dal Hub; categoria euristica; copie e file accanto a `SKILL.md` che
restano dopo l'eliminazione di una skill della libreria; skill di progetto non lette; limite anonimo di
GitHub; skill personali non collegate alla loro fonte online; knowledge non copiate nelle app;
la sincronizzazione automatica non rilegge la cartella della libreria. TD-59 si è ripetuto (nomi degli
schemi OpenAPI), trovato ed evitato.
