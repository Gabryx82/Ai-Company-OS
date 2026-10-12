# ADR-040 — «Apri con» e il menu contestuale dei file

- **Stato**: Accettata e implementata
- **Data**: 2026-10-13
- **Decisa da**: agente, su direttive umane del 2026-10-12 («un pulsante "Apri con" che permetta di scegliere l'IDE
  o il programma con cui aprire il file selezionato, tra quelli del software hub. Il sistema deve suggerire
  automaticamente il programma più adatto in base all'estensione e alla tipologia del file, aggiornando il
  suggerimento a ogni nuova selezione»; «un menu a tendina con rinomina, rimuovi ecc. se clicco con il tasto destro
  su un file»)
- **Rivede**: ADR-039 §5 (l'explorer non era più di sola lettura); ADR-019 I2 (un secondo argomento variabile: un
  file); ADR-020 W3 per la **scrittura** dall'explorer; ADR-035 §5 (un'API che toglie file scelti dall'operatore,
  ma verso il Cestino). **Usa**: ADR-020 W2, ADR-035 §3 (anteprima e dialog unici).

## 1. «Apri con»

Il visualizzatore ha un pulsante doppio: **«Apri con ‹programma consigliato›»** apre il file con un clic; la freccia
accanto elenca ogni programma del Software Hub che apre file. Lo stesso elenco è nel menu contestuale («Apri con
‹consigliato›» e «Apri con…»). Il suggerimento si ricalcola a ogni file selezionato, e quando il visualizzatore ha
letto il file sa anche se è testo.

### 1.1 Il catalogo dice come e per che cosa (V41)

| Campo | Che cosa | Esempi |
|---|---|---|
| `fileArgs` | gli argomenti che aprono un file, dopo gli `executableArgs`; `{file}` esattamente una volta, `{folder}` (la cartella del progetto) al più una, **sempre argomenti interi** | VS Code e Antigravity IDE `{folder} {file}` (la cartella come workspace, il file al suo interno); NetBeans `--open {file}`; gli altri `{file}` |
| `fileTypes` | i file di cui il programma è la scelta giusta: `.java` un'estensione, `pom.xml` un nome, `*` qualsiasi testo | IntelliJ `.java .kt .gradle .sql pom.xml…`; WebStorm `.ts .tsx .css package.json…`; PyCharm `.py .ipynb requirements.txt…`; Visual Studio `.sln .csproj .cs .cpp…`; Notepad++ `.txt .log .ini .csv *`; Blender `.blend`; MySQL Workbench `.mwb`; VS Code una lista larga e `*` |
| `opensFiles` (risposta) | il programma può aprire un file **adesso**: installato, con l'eseguibile risolto | falso per un'app trovata solo dal menu Start |

Gli argomenti sono stati verificati sulle associazioni di Windows della macchina dell'operatore
(`.mwb` → `MySQLWorkbench.exe "%1"`, `.blend` → `blender-launcher.exe "%1"`) e sulle CLI documentate (VS Code,
JetBrains, `devenv`, NetBeans `--open`). Antigravity IDE apre file ma non ne dichiara: compare fra gli altri.

### 1.2 Il suggerimento

1. la corrispondenza più precisa: il **nome** del file, poi l'**estensione** (la più lunga), poi «qualsiasi testo»
   (`*`, solo se il file non è binario);
2. a parità, il programma **più specializzato**, quello che dichiara meno tipi (WebStorm prima di VS Code per `.ts`,
   PyCharm prima di VS Code per `.py`, Notepad++ prima di VS Code per un file di testo qualsiasi);
3. poi il nome, perché l'ordine non cambi per caso.

Solo un programma che può aprire il file ora è consigliato; quelli che sarebbero adatti ma non possono sono elencati
in fondo con il motivo («Non installato», «Aperto dal menu Start: non riceve un file»). Il calcolo è nella console
(`fileOpeners.ts`), sui dati del catalogo letti al più una volta al minuto.

### 1.3 Il lancio: I2 allargato di un argomento

`POST /api/software/{key}/launch` accetta `{projectId, path}`. Il percorso è **relativo**, passa da
`WorkspacePaths.inside` (W2, link compresi) e deve essere un file regolare; il piano (`LaunchPlan.forFile`) mette il
percorso **assoluto** dove il catalogo ha `{file}` — mai un percorso che inizi con `-`, mai metà di un argomento. In
più:

- **solo un `.exe`** riceve un file: un `.cmd` o `.bat` passerebbe da `cmd.exe`, che legge `&` e `%` di un nome di
  file come comandi;
- **mai il programma predefinito di Windows**: su questa macchina `.py` è associato a `py.exe`, che lo *eseguirebbe*;
- una voce trovata solo tramite AppID non riceve file (`explorer.exe shell:AppsFolder\…` non prende argomenti).

## 2. Il menu contestuale

Tasto destro (o il tasto menu, o Maiusc+F10) su un elemento dell'albero; sullo spazio vuoto per la cartella del
progetto.

| Su | Voci |
|---|---|
| file | Apri · Apri con ‹consigliato› · Apri con… · Mostra in Esplora risorse · Copia percorso · Copia percorso relativo · Rinomina… (F2) · Sposta nel Cestino (Canc) |
| cartella | Nuovo file… · Nuova cartella… · Mostra in Esplora risorse · Copia percorso · Copia percorso relativo · Rinomina… · Sposta nel Cestino |
| spazio vuoto | Nuovo file… · Nuova cartella… · Mostra in Esplora risorse · Copia percorso |
| collegamento | Mostra in Esplora risorse · Copia percorso · Copia percorso relativo (Rinomina e Cestino disattivati) |

Il menu è disegnato nel `<body>` (portal), non nel pannello: il pannello è `sticky`, quindi un contesto di
sovrapposizione suo, e un menu al suo interno — qualunque `z-index` — finiva sotto la pagina accanto (visto
dall'operatore il 2026-10-13). Resta sempre dentro la finestra: si sposta a sinistra o in alto se sborderebbe, e
scorre se è più alto della finestra.

Rinomina e Nuovo si scrivono **nell'albero**, come nei file manager: Invio conferma, Esc rinuncia, uscire dal campo
conferma (se l'ultimo tentativo è stato rifiutato, rinuncia). Per un file è selezionato il nome senza estensione. Un
nuovo file si apre nel visualizzatore, con «Apri con» già pronto. Un file rinominato o tolto mentre è nel
visualizzatore viene seguito o chiuso.

### 2.1 API

| Endpoint | Che cosa |
|---|---|
| `POST /api/projects/{id}/browse/rename` `{path, name}` | un nuovo nome nella stessa cartella → l'elemento |
| `POST /api/projects/{id}/browse/create` `{parent, name, kind}` | un file o una cartella vuoti → `201` |
| `GET /api/projects/{id}/browse/deletion?path=` | il `DeletionPreview` (ADR-035 §3) dello spostamento nel Cestino |
| `DELETE /api/projects/{id}/browse?path=` | nel Cestino di Windows → `204` |
| `POST /api/projects/{id}/browse/reveal` `{path}` | una finestra di Esplora risorse con l'elemento selezionato → `204` |

Operatore e admin: niente si distrugge, quindi non è un'eliminazione «da admin» (ADR-024 §3).

### 2.2 Regole

- **R1 — Niente si distrugge.** Togliere è `SHFileOperation` con `FOF_ALLOWUNDO`: il Cestino, da cui l'operatore
  ripristina. Solo dischi fissi (altrove Windows cancellerebbe per sempre); oltre 10.000 elementi o 1 GB una cartella è
  rifiutata (il Cestino potrebbe non contenerla); `FOF_WANTNUKEWARNING` fa chiedere a Windows sul desktop se dovesse
  comunque distruggere; `FOF_NORECURSEREPARSE` non tocca ciò a cui punta un link. Dove non c'è un Cestino (fuori da
  Windows) l'anteprima lo dice e non si toglie nulla.
- **R2 — Mai sopra un nome.** Rinomina e Nuovo non sovrascrivono: `409 workspace-entry-exists`. Un cambio solo di
  maiuscole passa da un nome intermedio, perché per Windows è lo stesso nome.
- **R3 — Un nome è un nome.** Niente `\ / : * ? " < > |`, caratteri di controllo, `.`/`..`, spazio o punto finale,
  nomi di dispositivo (`CON`, `NUL`, `COM1`…), i nomi riservati di AI Company OS: `400 validation-failed`.
- **R4 — Ciò su cui AI Company OS sta in piedi resta.** La struttura del workspace (ADR-035 §6: `MASTER_PROMPT.md`,
  `AGENTS.md`, `CLAUDE.md`, `.aicos/` e i suoi file) e `.git` non si rinominano né si tolgono (`400`); in `.git` non
  si crea. Un documento a cui punta una task, una fase o un handoff — o una cartella che ne contiene uno — non si
  rinomina (`409 workspace-entry-in-use`) e non si toglie (blocco dell'anteprima, `409 deletion-blocked`).
- **R5 — Un collegamento è dell'operatore**: si vede e si apre se resta dentro, ma si rinomina o si toglie da
  Esplora risorse, dove si vede anche a che cosa punta.
- **R6 — Un progetto archiviato si legge**, non si cambia (`409 archived-project-is-immutable`); «Mostra in Esplora
  risorse» resta, perché non cambia nulla.
- **R7 — Un file aperto altrove** (Windows rifiuta lo spostamento o il nome) è `409 workspace-entry-in-use` con il
  motivo.

### 2.3 Esplora risorse

`SHOpenFolderAndSelectItems` apre la cartella con l'elemento selezionato. **Non** `explorer.exe /select,<percorso>`:
con uno spazio nel percorso Java mette tra virgolette l'argomento intero ed Explorer apre *Documenti* (riprodotto il
2026-10-13), e una virgola nel nome lo rompe comunque. Se la shell fallisce, Explorer apre la cartella che contiene
l'elemento — una cartella, mai un file, che Explorer eseguirebbe. Segue l'interruttore del launcher (ADR-019 I6).

## 3. Che cosa non fa

Non modifica il contenuto dei file (il visualizzatore resta di sola lettura: si modifica con «Apri con»); non copia,
non sposta fra cartelle, non lavora su selezioni multiple; non apre file con programmi fuori dal Software Hub né con
il programma predefinito di Windows; non elimina per sempre.

## 4. Test

Backend: `LaunchPlanTest` (+5: argomenti al loro posto, file fuori o cartella rifiutati, solo `.exe`, segnaposto
interi, voce disattivata), `OpenWithApiTest` (5: catalogo, lancio con file, percorsi fuori o mancanti, programma
senza file o da menu Start, validazione di `fileArgs`), `ProjectFileOperationsApiTest` (14: rinomina di file e
cartelle e di sole maiuscole, mai sopra un nome, nomi rifiutati, struttura e git, documenti delle task, uscite dalla
cartella, creazione, anteprima, Cestino, blocchi, nessun Cestino, file in uso, progetto archiviato, Esplora risorse);
coperture `ApiProblemCoverageTest`, `SchemaMigrationTest`, `OpenApiContractTest` (contratto additivo).
Console: `fileOpeners.test.ts` (7: suggerimento per tipo e che segue il file, nome prima dell'estensione,
specializzazione, testo e binari, programmi non disponibili, programmi senza file, etichette) e `FileExplorer.test.tsx`
(+10: pulsante e menu del visualizzatore, voci del menu e tastiera, «Apri con…» e Indietro, apri/copia/Esplora,
rinomina che il visualizzatore segue, F2/Esc e nome rifiutato, Cestino, nuovo file e nuova cartella, progetto
archiviato).

Mutazioni viste rosse: il controllo dei documenti delle task tolto dalla rinomina, il controllo `.exe` tolto dal
piano, la specializzazione tolta dall'ordinamento, `*` accettato per i binari.

Smoke dal vivo (porta 8091, database `aicompany_p40`, console 5180, cartelle nello scratchpad): i dieci programmi
rilevati con l'eseguibile; `App.java` → «Apri con IntelliJ IDEA», `pom.xml` → IntelliJ «Fatto per Progetto Maven»,
`.txt` → Notepad++; «Apri con Notepad++» su `server.log` ha avviato `notepad++.exe <file>`; F2 ha rinominato un file e
il visualizzatore lo ha seguito; «Sposta nel Cestino» lo ha messo nel Cestino di Windows con la posizione d'origine;
«Mostra in Esplora risorse» su `con spazio, e virgola.txt` ha aperto la cartella con il file selezionato (dopo la
correzione del §2.3).
