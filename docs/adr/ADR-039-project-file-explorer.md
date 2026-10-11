# ADR-039 — File dei progetti: un explorer di sola lettura nella console

- **Stato**: Accettata e implementata
- **Data**: 2026-10-11
- **Decisa da**: agente, su direttiva umana del 2026-10-11 («un file tree explorer, in modo che io possa visualizzare
  tutti i file, di ogni progetto, senza dover aprire gli IDE ma direttamente nell'interfaccia di AI-COMPANY-OS. Fai
  in modo che possa aprire il file manager sulla sinistra cliccando un bottone»)
- **Rivede**: ADR-020 W3 per la **lettura** (la scrittura resta com'è). **Usa**: ADR-020 W2.

## 1. Decisione

Un bottone **File** nella barra in alto apre, a sinistra accanto alla navigazione, il pannello dei file. Il pannello
sceglie il progetto (quelli con una cartella: quello scelto nel pannello, altrimenti quello della pagina aperta, poi
l'ultimo scelto) e ne mostra
l'albero: le cartelle si leggono una alla volta, quando si aprono; un filtro restringe i nomi già letti. Un clic su un
file lo apre **al posto della pagina**, che resta montata sotto e torna con *Chiudi*, `Esc` o scegliendo un'altra
sezione. Lo stato aperto/chiuso del pannello e l'ultimo progetto restano nel browser (`localStorage`).

Il visualizzatore mostra il testo con i numeri di riga (o a capo automatico), le immagini, e per il resto dice perché
non lo mostra. *Copia percorso* copia il percorso relativo.

## 2. API, sola lettura

| Endpoint | Che cosa |
|---|---|
| `GET /api/projects/{id}/browse[?path=]` | una cartella: cartelle prima, poi file per nome; tipo (`DIRECTORY`, `FILE`, `LINK`), dimensione, data; al più 5000 voci (`truncated`) |
| `GET /api/projects/{id}/browse/file?path=` | un file: `TEXT` con il contenuto, oppure `IMAGE`, `BINARY`, `WITHHELD`, `TOO_LARGE` con il motivo |
| `GET /api/projects/{id}/browse/image?path=` | i byte di un'immagine (`no-store`); qualunque altro file è rifiutato |

Operatore e admin. Nessun endpoint scrive.

## 3. Regole

- **F1 — Dentro la cartella** (W2): ogni percorso passa da `WorkspacePaths.inside`: `..`, percorsi assoluti, nomi di
  dispositivo e link che portano fuori sono `400 workspace-path-refused`. Un link (simbolico o junction) è elencato
  come `LINK` e si apre solo se resta dentro.
- **F2 — Le credenziali non si leggono**: `.env` e `.env.*` (non `.env.example`, `.sample`, `.template`, `.dist`,
  `.defaults`), `local.env`, `.npmrc`, `.pypirc`, `.netrc`, `.git-credentials`, `credentials[.json]`,
  `secret(s).*`, `id_rsa`/`id_ed25519`…, `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.ppk`,
  `*.kdbx` sono elencati ma il contenuto non viene mai letto: `WITHHELD`.
- **F3 — Testo è UTF-8 senza byte nulli** (il BOM si toglie), fino a 2 MB; oltre è `TOO_LARGE`, altrimenti `BINARY`.
- **F4 — Solo le immagini come byte** (`png`, `jpg`, `gif`, `webp`, `bmp`, `ico`, fino a 10 MB). Un SVG è testo:
  può contenere uno script, quindi non si serve come immagine.
- **F5 — Cartella mancante**: progetto senza cartella `409 workspace-not-configured`; cartella che non esiste più
  `503 workspace-unavailable`.

## 4. Revisione di ADR-020 W3

W3 diceva che l'API legge soltanto i documenti del progetto e le immagini di `references/`, perché «il codice del
progetto non è affare di questa API». Per la **lettura** non è più vero: l'operatore vuole vedere ogni file senza
aprire un IDE. Resta vero per la **scrittura**: l'API scrive ancora soltanto i documenti di W3.

## 5. Che cosa non fa

Non modifica, crea, rinomina o elimina file; non cerca nel contenuto; non mostra lo stato git; non segue i link fuori
dalla cartella; non evidenzia la sintassi (testo monospazio con numeri di riga).

## 6. Test

`ProjectFileBrowserApiTest` (come operatore: ordine dell'elenco, testo senza BOM, credenziali elencate e mai lette
né servite come byte, binari e file grandi, immagini come byte e nient'altro, ogni uscita dalla cartella per i tre
endpoint, link verso l'esterno elencato e non seguito, progetto senza cartella e cartella sparita);
`FileExplorer.test.tsx` (progetti con cartella e quello in vista per primo, cartella letta solo quando si apre, filtro,
apertura nel visualizzatore, credenziali, immagine, `Esc`, il bottone della barra e la pagina nascosta finché si
cambia sezione). Mutazioni viste rosse: credenziali lette, cartella fuori elencata, BOM conservato. Smoke dal vivo
(porta 8091, console 5180): un progetto sulla cartella di AI Company OS, `frontend/` aperta, `package.json` mostrato;
a 375 px il pannello si sovrappone senza scorrimento orizzontale.
