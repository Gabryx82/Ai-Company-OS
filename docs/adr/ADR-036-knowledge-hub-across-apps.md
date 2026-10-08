# ADR-036 — Il Knowledge Hub come punto unico: le skill di ogni app, i marketplace, la sincronizzazione

- **Stato**: Accettata e implementata (PHASE 31)
- **Data**: 2026-10-07
- **Decisa da**: agente, su direttiva umana del 2026-10-07 («trasformare il Knowledge Hub in un punto
  unico» per le skill e le knowledge di tutti gli agenti, «senza rimuovere o modificare negativamente ciò
  che già esiste»; poi: «dividere le skill di Code, Codex (ChatGPT) e ecc. per tipologia»; poi: «le nuove
  skill e plugin che aggiungo a Claude Desktop e a ChatGPT non si vedono», «da marketplace non posso
  installare una skill su ChatGPT», «nel marketplace ci sono solo skill: voglio anche plugin, connettori»)
- **Estende**: ADR-026 (skill e knowledge come file), ADR-023 (harness). Non modifica ADR-035.

## 1. La direttiva, e che cosa ne è diventato

| Obiettivo | Come |
|---|---|
| vedere tutte le skill e knowledge installate | scheda **Installate**: la libreria di AI Company OS e ogni app di questo PC, una sola vista (§2) |
| vedere anche quelle di Claude Desktop, Codex, ecc. | uno scanner per ogni forma di cartella: personali, plugin, di sistema, manifest per account (§2) |
| divise per app e per tipologia | app → tipologia (libreria, personali, plugin uno per uno, di sistema, knowledge, istruzioni globali) → categoria funzionale (§4) |
| tenerle sincronizzate fra le fonti | la libreria è il centro: si importa dalle app, si copia nelle app, ogni copia porta un marcatore e resta allineata nei due sensi (§5); una sincronizzazione ogni 5 minuti registra installazioni, rimozioni e modifiche (§9) |
| verificare e installare gli aggiornamenti | scheda **Aggiornamenti**: pacchetti dei marketplace, skill importate dalle app, copie da allineare, plugin delle app (§8) |
| esplorare marketplace e cataloghi, anche della community | scheda **Marketplace**, organizzata come il Mockup Hub: una ricerca, uno scaffale di provider (§6) |
| cercare e installare dall'interfaccia | skills.sh, ClawHub e i repository ufficiali (Anthropic, OpenAI, Vercel) si cercano e si installano dal Hub, con anteprima dei file (§6) |
| mantenere ciò che esiste | la vista di PHASE 19 è la scheda **Libreria e catalogo**, invariata; il dettaglio di una skill della libreria guadagna la sezione «Nelle app» |

## 2. Dove le app tengono skill, plugin, connettori e knowledge — e chi li possiede

> **Rivisto il 2026-10-07, dopo la verifica dell'operatore** («per Claude non vedo i plugin che erano già
> installati»). Due cause. (1) **Claude Desktop è un'app del Microsoft Store (MSIX)**: Windows virtualizza
> il suo `%APPDATA%\Claude`. L'app e i processi che lancia lo vedono lì; ogni altro processo — il control
> plane, avviato da PowerShell — trova i dati in
> `%LOCALAPPDATA%\Packages\Claude_<editore>\LocalCache\Roaming\Claude`. Lo smoke era partito da dentro
> l'app (questa sessione) e vedeva 228 voci; il backend dell'operatore ne vedeva **zero**. Ora entrambe le
> sedi sono candidate e vale quella che Claude Desktop ha scritto per ultima. (2) Il Hub elencava **solo
> le skill**: un plugin fatto di hook (security-guidance), i **connettori** (server MCP) e la **memoria**
> di Claude Code non comparivano. Ora plugin, connettori e knowledge sono elementi a sé (`kind`).


Ogni percorso è calcolato dal server a partire dalla home dell'utente e da `%APPDATA%`
(`aicos.hub.home`, `aicos.hub.app-data`), mai preso da una richiesta (`SkillLocations`).

| App | Cartella | Tipologia | Il Hub scrive? |
|---|---|---|---|
| Claude Code | `~/.claude/skills/<nome>/SKILL.md` | personali | **sì** |
| | `~/.claude/plugins/installed_plugins.json` → percorso del plugin | plugin | no |
| | `~/.claude/CLAUDE.md` | istruzioni globali | no |
| | `~/.claude/settings.json` (`enabledPlugins`) → anche i plugin abilitati dalla copia del marketplace | plugin (attivo/disattivo) | no |
| | `~/.claude.json` → `mcpServers` (utente e per progetto), `claudeAiMcpEverConnected` | connettori: tuoi, dell'account claude.ai | no |
| | `~/.claude/projects/<progetto>/memory` | knowledge: la memoria di Claude Code per progetto | no |
| Claude Desktop | `<home Claude>\local-agent-mode-sessions\skills-plugin\<org>\<account>\manifest.json`, dove `<home Claude>` è `%APPDATA%\Claude` o, per l'app dello Store, `%LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude` | di sistema (Anthropic) o personali (`creatorType`) | no |
| | `…\<account>\<org>\rpm\manifest.json` → `plugin_<id>` | plugin con i loro componenti (skill, comandi, agenti, hook, `.mcp.json`) | no |
| | `<home Claude>\extensions-installations.json`, `claude_desktop_config.json` | connettori: estensioni desktop (attive/disattive), server MCP configurati | no |
| ChatGPT · Codex | `~/.codex/skills/.system` | di sistema | no |
| | `~/.codex/skills` | personali — dove installa anche lo skill-installer di Codex (`$CODEX_HOME/skills`) | **sì** (§2b) |
| | `~/.codex/plugins/cache/<marketplace>/<plugin>/<versione>` + `config.toml` | plugin (attivo/disattivo) | no |
| | `~/.codex/AGENTS.md` | istruzioni globali | no |
| | `~/.codex/config.toml` → `[mcp_servers.*]` | connettori | no |
| | `~/.codex/cache/codex_apps_tools/*.json` → `connector_name` | connettori dell'account: le **app di ChatGPT** collegate (Gmail, Figma…) | no |
| Agent Skills condivise | `~/.agents/skills` — la cartella utente che **Codex**, Gemini CLI, OpenCode e Copilot leggono | personali; nella vista di ChatGPT · Codex compaiono anche lì, come «lette anche da qui» | **sì** |
| Antigravity | `~/.gemini/antigravity/skills` | personali | **sì** |
| | `~/.gemini/antigravity{,-ide}/builtin/skills`, `~/.gemini/antigravity-ide/plugins` | di sistema, plugin | no |
| | `~/.gemini/antigravity/knowledge` | knowledge items | no |
| | `~/.gemini/config/mcp_config.json` | connettori | no |
| Gemini CLI | `~/.gemini/skills`, `~/.gemini/extensions`, `~/.gemini/GEMINI.md` | personali, plugin, istruzioni | sì / no / no |
| OpenCode | `~/.config/opencode/skills` (e `skill/`), `AGENTS.md` | personali, istruzioni | sì / no |
| Verdent, Copilot CLI, Cursor | `~/.verdent/skills`, `~/.copilot/skills`, `~/.cursor/skills` | personali | **sì** |

**Perché solo le cartelle personali sono scrivibili.** I plugin, le skill di sistema e la cache di Claude
Desktop sono dell'app: li scrive il suo installatore, li riscrive il suo aggiornamento, e Claude
Desktop li riceve dall'account. Scriverci da fuori produrrebbe stati che l'app non conosce e che la
prossima sincronizzazione dell'app cancellerebbe. Per Claude Desktop il Hub prepara invece lo `.zip`
che si carica da *Impostazioni → Capacità → Skill*.

Di Claude Desktop si elenca solo l'account **in uso** (il manifest aggiornato per ultimo); gli altri si
contano in una nota. Una cartella-plugin di Codex chiamata con un id (`app-6a32…`) prende il nome dal
suo manifest (`interface.displayName`).

**Plugin** = un elemento per plugin, anche senza skill, con i suoi componenti contati (skill, comandi,
agenti, hook, connettori). **Connettori** = un elemento per nome e per app: Slack dichiarato da otto plugin
di Claude Desktop è un connettore «fornito da» otto plugin. Di un server MCP il Hub tiene nome, tipo,
il nome dell'eseguibile o host e percorso dell'URL — **mai argomenti, variabili d'ambiente, header o
query string**, dove stanno i token; il dettaglio di un connettore non legge mai il file di
configurazione (in `~/.claude.json` c'è anche l'account). I connettori dell'account claude.ai sono noti
localmente solo per nome, da ciò che Claude Code ha usato (`claudeAiMcpEverConnected`).

Misurato su questo PC il 2026-10-07, con la vista «da fuori» del pacchetto: Claude Desktop **13 plugin**
(security-guidance compreso), **228 skill**, **70 connettori** e l'estensione Blender; Claude Code il
plugin frontend-design, 4 server MCP tuoi più «Claude Docs» dell'account, la memoria di 2 progetti; Codex
25 plugin, 65 skill, 7 connettori; Antigravity 20 skill, 4 connettori. La lettura completa resta sotto
1,5 s (0,4 s con la cache dei `SKILL.md`).

## 2b. ChatGPT · Codex, e le skill caricate in Claude Desktop

> **Aggiunto il 2026-10-07, dopo la seconda verifica dell'operatore** («le nuove skill e plugin che
> aggiungo a Claude Desktop e a ChatGPT non si vedono nell'hub»; «da marketplace non posso installare una
> skill su ChatGPT»).

- **Le skill caricate in Claude Desktop** hanno nel manifest un id `skill_01…` e la cartella col loro
  **nome**: il Hub cercava la cartella per id e le saltava. Ora, se la cartella dell'id non c'è, usa
  quella del nome. Misurato: emil-design-eng e frontend-design ora compaiono (230 skill).
- **ChatGPT desktop non tiene skill né plugin sul PC**: le sue app e i suoi connettori sono dell'account.
  Sul PC li conosce **Codex**, l'app di OpenAI inclusa nell'abbonamento ChatGPT, che li mette in cache
  (`codex_apps_tools`): il Hub li elenca come connettori dell'account, uno per app, con il numero di
  strumenti. Misurato: 16 app di ChatGPT, più i 7 server MCP di Codex.
- **Installare «su ChatGPT»** significa installare in Codex: l'app si chiama ora **ChatGPT · Codex** e
  `~/.codex/skills` è la sua cartella scrivibile (è anche dove lo skill-installer di Codex installa).
  Le skill installate prima in `~/.agents/skills` restano dove sono e compaiono nella vista di ChatGPT ·
  Codex, che le legge, sotto «Lette anche da qui».

## 3. Leggere un `SKILL.md`

Il frontmatter delle app non è quello della libreria (ADR-026 §1): ha `name` (un identificatore) e
`description`, spesso su più righe (`description: >-`), niente `key` né `kind`. `Frontmatter` legge il
sottoinsieme di YAML che le skill usano — valori semplici, tra virgolette, a blocchi, un livello di
annidamento (`metadata.version`), liste — e lascia assente ciò che non capisce.

`SkillDocument` usa ora lo stesso lettore: i file della libreria si leggono come prima (un ` #` resta
nel valore, come prima), e in più una descrizione a blocchi si legge intera. **Difetto corretto**:
l'import da URL di un `SKILL.md` standard (frontmatter senza `key`) era rifiutato; ora la chiave viene
dal nome, e al frontmatter si aggiunge solo la riga `source:` invece di riscriverlo (licenza e
metadati dell'autore restano).

## 4. Tipologie e categorie

Due assi, perché la direttiva chiede di dividere «per tipologia» e una sola parola non basta:

- **tipologia** — che cosa la skill è per l'app: libreria, personali, plugin (un gruppo per plugin, con
  versione e marketplace), di sistema, knowledge, istruzioni globali;
- **categoria funzionale** — a che cosa serve: documenti e Office, giochi e 3D, design, sicurezza, DevOps
  e cloud, business e marketing, dati, produttività, browser e automazione, AI e agenti, sviluppo,
  altro. Euristica a parole chiave (`SkillCategories`): il nome e il plugin pesano più della
  descrizione, che cita di tutto; la prima regola che corrisponde vince. Su questo PC: 1 voce su 323 in
  «Altro».

## 5. La libreria al centro: provenienza, copie, impronte

Sincronizzare «fra le fonti» qui significa: **la libreria di AI Company OS è il centro**, le app sono
fonti e destinazioni.

- **Dentro**: una skill si installa da un marketplace o si **importa** da un'app. La voce della libreria
  ricorda da dove viene (`skill_installations`, V30: provider, riferimento canonico, versione,
  impronta a monte, impronta di ciò che il Hub ha scritto).
- **Fuori**: una skill della libreria si **copia** nella cartella personale di un'app. Nella copia il Hub
  lascia `.aicos-hub.json` (chiave, impronta della libreria e della copia al momento della scrittura);
  le app ignorano i file nascosti. Una skill della libreria in formato AI Company OS (`key`, `kind`,
  `name` descrittivo) arriva alle app con `name` = chiave e il titolo sotto `metadata`, con valori tra
  virgolette che anche un lettore YAML rigoroso accetta; una in formato standard arriva com'è.
- **Stato di una copia**, dal confronto delle impronte: *sincronizzata*, *da aggiornare* (è cambiata la
  libreria), *modificata nell'app* (si può riportare nella libreria, e le altre copie intatte seguono),
  *conflitto* (sono cambiate entrambe), *non più in libreria*.

**Impronte**: `content` (ogni byte di ogni file, per percorso) dove il Hub decide se può sovrascrivere;
`quick` (il `SKILL.md` e nome e dimensione degli altri file) per la sincronizzazione periodica.

**Mai sovrascrivere senza saperlo.** Una cartella senza marcatore, una copia modificata nell'app, una
libreria modificata dopo l'installazione: il Hub rifiuta (`409 skill-hub-conflict`) e dice che cosa;
con la conferma esplicita sovrascrive e **tiene un backup**. Questa è la precondizione delle scritture
del Hub — sul contenuto, più forte di un tag di versione — ed è dichiarata in
`PreconditionCoverageTest`.

## 6. Marketplace

Organizzati come il Mockup Hub (ADR-032): una ricerca, uno scaffale. Catalogo in
`catalog/skill-marketplaces.json`.

| Provider | Come | Installabile dal Hub |
|---|---|---|
| **skills.sh** | `GET https://skills.sh/api/search?q=` → `owner/repo/skill`, risolto nella cartella del repository GitHub con l'albero del repository | sì |
| **ClawHub** | `GET https://clawhub.ai/api/v1/search`, dettaglio, versioni con elenco file, sicurezza; file per file | sì (le skill sospette sono segnalate) |
| **Anthropic, OpenAI, Vercel** (repository ufficiali) | albero via `api.github.com`, file da `raw.githubusercontent.com` | sì |
| directory Claude, SkillsMP, claude-plugins.dev, LobeHub, Smithery, aitmpl, MCP Market, Tessl, Awesome Claude Skills, agentskills.io | si aprono nel browser, con la ricerca quando il sito la accetta | no (si importa poi l'URL del `SKILL.md`) |

Difese: solo HTTPS verso quattro host (`HubWeb.HOSTS`), nessun redirect seguito, URI costruiti dal
server con parti validate; nel pacchetto solo percorsi relativi e semplici (niente `..`, unità, nomi di
dispositivo), al massimo 1000 file, 16 MB l'uno, 64 MB in tutto (erano 150 file, 1 MB, 8 MB: rifiutavano 10 delle
341 skill di questo PC, fra cui `impeccable` con un indice di font di 1,05 MB; la più grande misurata è di 7 MB); link simbolici e file nascosti esclusi;
l'anteprima mostra file, `SKILL.md` e un avviso quando ci sono script. Nessuna credenziale, nessun
costo: le API usate sono pubbliche e in sola lettura. GitHub anonimo concede 60 richieste all'ora
sull'API: gli alberi restano in cache 10 minuti e i frontmatter per sempre (per sha); il limite
raggiunto si dice con l'ora di ripristino.

## 6b. Plugin e connettori nel Marketplace

> **Aggiunto il 2026-10-07** («nel marketplace ci sono solo skill: voglio anche plugin, connettori»).

La scheda Marketplace ha tre schede — **Skill**, **Plugin**, **Connettori** — ognuna con la sua ricerca,
i suoi cataloghi installabili e le sue directory da aprire nel browser (`kind` nel catalogo).

| Catalogo | Fonte | Si installa in |
|---|---|---|
| **Claude Code · plugin ufficiali** | `anthropics/claude-plugins-official`, `.claude-plugin/marketplace.json` (oltre 300 plugin) | Claude Code |
| **Anthropic · plugin di lavoro** | `anthropics/knowledge-work-plugins` | Claude Code; Claude Desktop dall'app |
| **OpenAI · plugin per ChatGPT · Codex** | `openai/plugins`, `.agents/plugins/marketplace.json` (`openai-curated`); nome e descrizione dal `plugin.json` di ognuno | ChatGPT · Codex |
| **Registro ufficiale MCP** | `registry.modelcontextprotocol.io/v0/servers` | Claude Code, ChatGPT · Codex, Claude Desktop |
| Claude Code Marketplaces, claude-plugins.dev; directory dei connettori di Claude, app di ChatGPT, Smithery, mcp.so, Glama, PulseMCP, server di riferimento MCP | si aprono nel browser | — |

**Come si installa: con la CLI dell'app, come farebbe l'operatore** (`HubInstaller`, `HubCommands`):

- plugin per Claude Code: `claude plugin marketplace add <owner/repo>` se il marketplace non è fra quelli
  noti (`known_marketplaces.json`), poi `claude plugin install <plugin>@<marketplace> --scope user --json`;
- plugin per Codex: `codex plugin marketplace list --json`, `add` se manca, poi `codex plugin add <plugin>@<marketplace> --json`;
- connettori: `claude mcp add --scope user` (remoti con `--transport http|sse` e `--header`; locali con
  `-e` e `-- cmd /c npx …` su Windows), `codex mcp add` (`--url`; locali con `--env`);
- Claude Desktop: un connettore **locale** si aggiunge a `claude_desktop_config.json` (quello del pacchetto
  dello Store), conservando tutto il resto del file e con un backup in `<aicos.home>/backups/app-config`;
  i plugin e i connettori **remoti** di Claude Desktop vengono dall'account: il Hub dice dove aggiungerli
  e apre l'app.

Un connettore del registro è installabile quando ha un indirizzo HTTPS senza parti da completare
(preferito lo streamable HTTP, poi SSE) o un pacchetto npm, PyPI o container avviabile senza argomenti
obbligatori; altrimenti la scheda dice «da configurare a mano» e perché.

**Difese.**
- Nessuna shell: ogni comando è una lista di argomenti costruita da parti validate (nomi di plugin e
  marketplace, nomi di server, pacchetti, versioni, variabili, URL). Se la CLI è un `.cmd` (Claude Code
  installato con npm), che `cmd.exe` legge come una riga di comando, un argomento con `& | < > ^ %` è
  rifiutato; virgolette e caratteri di controllo sempre.
- **Un comando dichiarato dal marketplace non parte senza l'operatore**: Claude Code lo riporta
  (`shownCommand`), il Hub lo mostra e lo esegue solo con la conferma (`--accept-command <sha256>`), che
  vale per quel comando e nient'altro.
- **Le chiavi non restano**: i valori dati a un connettore (API key in un header o in una variabile)
  vanno all'app e basta — non si salvano, non si registrano, e da comandi, output e messaggi restituiti
  sono oscurati (`••••`). La console li tiene in campi password e li dimentica dopo l'installazione.
- L'anteprima mostra, app per app, il comando che verrà eseguito; le app non trovate sul PC non si
  possono scegliere.

## 7. Scrivere una cartella

`HubFiles`: i file si preparano in `<aicos.home>/hub-staging`, la cartella che c'era si sposta intera
in `<aicos.home>/backups/skills/`, poi la preparata va al suo posto. **Niente si elimina**: anche
«Togli dall'app» è uno spostamento nei backup. Dentro una transazione, un rollback rimette le cose
com'erano. Una scrittura alla volta.

## 8. Aggiornamenti

| Tipo | Si accorge | Si applica |
|---|---|---|
| Pacchetto di un marketplace | impronta a monte cambiata (albero GitHub: sha dei file; ClawHub: versione) | dal Hub; se la libreria è stata modificata, solo con conferma (backup) |
| Skill importata da un'app | impronta della sorgente cambiata | dal Hub, come sopra |
| Copia nell'app | §5 | dal Hub: *aggiorna la copia* o *riporta nella libreria* |
| Plugin di Claude Desktop | versione pubblicata nel marketplace `anthropics/knowledge-work-plugins` (e nei repository che indica) **strettamente** più recente di quella installata | **dall'app**: Claude Desktop aggiorna i suoi plugin; il Hub lo dice e la apre |
| Plugin di Claude Code | versione del marketplace clonato localmente strettamente più recente | dall'app: il Hub mostra `claude plugin update <plugin>@<marketplace>` |

**Codex: nessuna verifica dei plugin.** Il catalogo che Codex tiene in `~/.codex/.tmp/plugins` è di un
altro canale e più vecchio delle versioni installate (misurato: `github` 0.1.0 nel catalogo, 0.1.12
installato): confrontarlo produrrebbe solo falsi segnali. Codex li aggiorna da sé.

Le verifiche online partono solo a richiesta («Verifica aggiornamenti»); le copie si confrontano a ogni
lettura.

## 9. Sincronizzazione

Ogni `aicos.hub.sync-interval` (5 minuti; `PT0S` la spegne, come nei test) e a richiesta, il Hub rilegge
le app e confronta con l'istantanea precedente (`skill_snapshot`): ciò che è comparso, sparito o
cambiato diventa un evento (`skill_sync_events`, gli ultimi 1000), mostrato nella scheda **Attività** —
anche quando a cambiare è un'app che aggiorna i suoi plugin. La prima lettura fotografa soltanto; una
riga di riferimento **per sede** (`baseline:<sede>`) lo ricorda: anche un PC senza skill vede
riportata la prima installazione, e una sede letta per la prima volta — una nuova app, o ciò che il Hub
ha imparato a leggere (Claude Desktop dallo Store, plugin, connettori) — viene fotografata invece di
comparire come centinaia di «installate oggi».

La console rilegge le app all'apertura della pagina e ogni minuto mentre la pagina è in vista.

**La sincronizzazione automatica non rilegge la cartella della libreria.** «Rileggi cartella»
(ADR-026 §2) aggiorna la versione di ogni voce: farlo ogni 5 minuti renderebbe obsoleti (`412`) gli
editor aperti nella console. Lo fa «Sincronizza ora», che è un gesto dell'operatore.

## 10. Permessi

Leggere, cercare, installare, importare, copiare, aggiornare: operatore. **Togliere una copia** da
un'app: admin (`DELETE /api/skill-hub/library/*/copies/*`), come ogni rimozione (ADR-035), anche se
la cartella resta come backup. Il Hub mostra il contenuto dei file di istruzioni globali (`CLAUDE.md`,
`AGENTS.md`) a chi usa la console, come già mostra i log d'uso di Claude Code e Codex.

## 11. API (tutta additiva)

`GET /api/skill-hub/installed[?rescan=true]`, `GET …/installed/{id}`, `POST …/installed/{id}/open`,
`POST …/installed/{id}/import`, `POST /api/skill-hub/sync`, `GET /api/skill-hub/events`,
`GET /api/skill-hub/marketplaces`, `GET …/marketplaces/search?q=&provider=`,
`GET …/marketplaces/{provider}/browse`, `GET …/marketplaces/{provider}/package?ref=`,
`POST /api/skill-hub/install`, `GET|POST|DELETE /api/skill-hub/library/{key}/copies[/{app}]`,
`GET /api/skill-hub/library/{key}/package.zip`, `GET /api/skill-hub/updates`,
`POST …/updates/check`, `POST …/updates/apply`; e (§6b) `GET …/marketplaces/search?…&kind=`,
`GET …/marketplaces/{provider}/item?ref=`, `POST /api/skill-hub/plugins/install`,
`POST /api/skill-hub/connectors/install`. Problemi nuovi: `skill-hub-item-not-found`,
`skill-package-refused` (422), `skill-hub-conflict`, `skill-location-read-only`,
`marketplace-unavailable` (502).

## 12. Verifica

- `SkillFilesTest` (8): frontmatter delle app, `SkillDocument` con descrizione a blocchi, percorsi dei
  pacchetti, categorie, resa per le app, versioni.
- `SkillHubApiTest` (12, dopo la revisione): Claude Desktop installato **dallo Store** (dati solo nella
  cartella del pacchetto), plugin senza skill, plugin di Claude Code con comandi, agenti e stato,
  connettori uniti per nome, estensione desktop disattivata, server MCP dell'utente e dell'account,
  memoria di progetto, **nessun segreto** (argomenti, env, credenziali e query di un URL, email
  dell'account) nell'elenco né nel dettaglio, connettore non importabile né apribile, versione di un
  plugin che cambia, sede nuova fotografata e non riportata; e inoltre ogni forma di cartella (personali, di sistema, plugin Codex con
  attivo/disattivo e nome dal manifest, Claude Desktop con account in uso e plugin, istruzioni), la
  sincronizzazione (anche su un PC vuoto), import e aggiornamento dalla sorgente, installazione da un
  marketplace in libreria e in due app, pacchetto che esce dalla cartella rifiutato senza scrivere
  niente, copie spinte e riportate con conflitto e backup, nessuna sovrascrittura senza conferma, zip,
  ricerca con un provider giù, aggiornamento a monte con modifica locale.
- `LibraryApiTest`: import di un `SKILL.md` standard senza chiave (visto rosso prima della correzione).
- **Mutazioni: 10 su 10 rosse** — ognuna delle guardie (cartella altrui, percorsi, conflitti di pull e
  di update, evento di rimozione, rimozione solo delle copie del Hub, solo admin, chiave dal nome,
  account in uso, copia modificata) tolta da sola fa fallire il suo test. Dopo la revisione, altre
  **7 su 7**: cartella dello Store, URL ripuliti, dettaglio del connettore senza file, plugin senza
  skill, sede nuova fotografata, connettori uniti per nome, connettore non importabile.
- Console: `hubModel.test` (raggruppamento, filtri, presenza nelle app), `SkillHub.test` (installate,
  import, marketplace con anteprima e installazione, aggiornamenti con conferma della sovrascrittura).
- Smoke dal vivo su un database separato con le cartelle reali di questo PC in sola lettura.
- §2b e §6b: `SkillHubApiTest` (skill caricata in Claude Desktop col nome, app di ChatGPT dalla cache di
  Codex, ChatGPT · Codex installabile e installazione in `~/.codex/skills`); `MarketplaceExtensionsApiTest`
  (3: cataloghi per tipo e anteprima app per app; plugin con la CLI e comando dichiarato confermato;
  connettore in tre app con la chiave mai restituita, file di Claude Desktop conservato con backup,
  rifiuti prima di eseguire); `HubCommandSafetyTest` (2: argomenti verso `cmd.exe`, URL remoti). Le CLI
  nei test sono un `FakeHubCommands`: nessun comando parte. **Mutazioni: 7 su 7 rosse** (oscuramento,
  conferma validata, file di Claude Desktop conservato, skill per nome, app di ChatGPT, Codex
  installabile, guardia di `cmd.exe`); console **4 su 4** (cartelle condivise, ricerca per tipo,
  conferma inviata, app assente non selezionabile).

## 13. Che cosa resta fuori

Registrato come debito: TD-74 … TD-82, TD-83 … TD-85, TD-86 … TD-90 (`docs/DEBT_REGISTRY.md`).

## Revisione PHASE 32 (2026-10-08) — la sincronizzazione automatica rilegge la libreria (TD-82)

Rileggere la cartella della libreria aggiornava ogni voce (`fileSyncedAt`, quindi la versione) anche
quando il file non era cambiato: per questo la sincronizzazione pianificata non la rileggeva, e un file
scritto a mano nella libreria entrava nell'indice solo con «Sincronizza ora» o «Rileggi cartella». Ora una
voce si tocca solo se il file dice qualcosa di nuovo (nome, descrizione, tag, sorgente, percorso); così la
sincronizzazione ogni 5 minuti rilegge anche la libreria, e un editor aperto su una voce diventa obsoleto
solo se quel file è cambiato davvero. `LibraryApiTest`, `SkillHubApiTest`.
