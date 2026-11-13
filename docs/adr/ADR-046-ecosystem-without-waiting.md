# ADR-046 — Un ecosistema che non fa aspettare

- **Stato**: Implementata, in attesa della review umana; non committata
- **Data**: 2026-11-12
- **Decisa da**: agente, su direttiva dell'operatore del 2026-11-12 («L'ecosistema è lento, mostra gli agenti in modo
  troppo lento. Velocizza le integrazioni e l'ecosistema openWebUI, LiteLLM, Ollama»)
- **Rivede**: ADR-043 §9 (la lista degli agenti di OpenClaw tenuta 10 s), ADR-045 §5.2 (le letture di OpenClaw),
  ADR-019 §3 (il rilevamento dei programmi). **Estende**: ADR-028 (l'avvio dell'ecosistema).

## 1. Che cosa era lento, misurato

Misure del 2026-11-12 su un backend isolato (8099) con i servizi veri dell'operatore: OpenClaw 2026.11.2, Open WebUI,
LiteLLM, Ollama, 3D Omniverse.

| Che cosa | Prima | Perché |
|---|---|---|
| Agenti → Ecosistema (`GET /api/agent-sync`) | **28 s**, di nuovo ogni volta che la lista di OpenClaw (tenuta 10 s) scadeva | la pagina aspettava `openclaw agents list`: 12–58 s a comando su questa macchina, **con o senza gateway**, perché il comando valida tutta la configurazione con i plugin e apre l'archivio di ognuno dei 20 agenti |
| Pagina OpenClaw alla prima visita | 12–60 s prima di vedere un agente | la stessa lista, letta per conto suo una seconda volta |
| `GET /api/software` (Software Hub, Dashboard, Agenti, Integrazioni, Terminale, ricerca) | 2 s a freddo, **di nuovo ogni 60 s**; 0,2–0,37 s a caldo | l'elenco del menu Start con PowerShell (~2 s) scadeva ogni minuto e si ricaricava dentro la richiesta; per ogni strumento a riga di comando, ogni cartella del `PATH` per ogni estensione controllata su disco a ogni lettura; i servizi chiesti uno dopo l'altro |
| Chat LLM (Open WebUI) e le altre app incorporate | Open WebUI ripartiva da capo a ogni visita | l'iframe si smontava cambiando pagina |

Non erano lenti, e restano come sono: Open WebUI, LiteLLM, Ollama e il gateway di OpenClaw rispondono in 1–40 ms
(`/health`, `/health/liveliness`, `/api/version`, `/api/tags`, `/healthz`); la pagina Modelli (40 ms); i servizi
dell'avvio (75 ms). Un servizio spento non costa: il client HTTP di Java sente il rifiuto in 2–39 ms (una `connect`
bloccante su Windows lo aspetta 2 s, ed è ciò che misurava `curl`). Nel browser `localhost` costa 20–30 ms in più di
`127.0.0.1` (il backend ascolta solo IPv4): poco, non cambiato. I `GET` della console non fanno preflight CORS.

## 2. Decisione

**Nessuna pagina aspetta uno strumento lento.** Ciò che costa si legge in background, si tiene, e la pagina mostra
l'ultima lettura con l'ora; la console chiede di nuovo finché una lettura è in corso.

1. **Una sola lettura degli agenti di OpenClaw**, nel connettore, condivisa dalla scheda Ecosistema e dalla pagina
   OpenClaw. Tenuta 30 s per la lettura (`FRESH`); più vecchia, la si mostra e la si rinnova dietro. Una scrittura
   (pubblica, porta qui, togli) la usa se ha meno di 60 s, altrimenti rilegge prima: una scrittura ha bisogno del vero.
2. **La panoramica chiede gli strumenti insieme** (thread virtuali): impiega quanto il più lento a rispondere *subito*,
   non la somma. Uno strumento mai letto ha le celle **`READING`** («lettura…»), non conta fra le cose da fare, e lo
   strumento dice `refreshing`; ogni strumento dice quando è stato letto (`checkedAt`).
3. **Il rilevamento dei programmi**: i servizi chiesti insieme (`SoftwareDetector.prepare`); l'elenco del menu Start
   tenuto 10 minuti e, una volta noto, rinnovato in background; dove sta un eseguibile o un comando tenuto 60 s come
   una sonda. «Aggiorna» del Software Hub guarda di nuovo tutto, alla lettura successiva.
   L'avvio dell'ecosistema (ADR-028), mentre aspetta che un servizio risponda, dimentica solo le risposte dei servizi
   (`forgetServices`): prima dimenticava tutto ogni due secondi, e ogni pagina aperta intanto rileggeva il menu Start.
4. **Riscaldamento all'avvio** (`EcosystemWarmUp`): appena il backend è pronto, in background, il rilevamento del
   catalogo e le letture di OpenClaw (agenti e sessioni). Solo letture; `aicos.warm-up=false` lo spegne (i test lo fanno).
5. **Le app incorporate restano caricate** dopo la prima apertura (Open WebUI in Chat LLM, 3D Omniverse), nascoste
   mentre l'operatore è altrove, come il Terminale (ADR-029 §5). «Ricarica» le ricarica a richiesta.

## 3. Invarianti

1. **Una lettura iniziata prima di una scrittura non la cancella.** Le scritture del connettore incrementano un
   contatore; una lettura che finisce dopo una scrittura non sostituisce la lista tenuta (che la contiene).
2. **Una scrittura non si fida di una lettura vecchia**: oltre 60 s rilegge prima di decidere fra creare e aggiornare.
3. **Ciò che non è stato letto non si spaccia per uno stato**: `READING`, non «da pubblicare».
4. **Solo letture in background**: il riscaldamento e i rinnovi non avviano, non scrivono, non pubblicano nulla.
5. **Una lettura fallita non toglie la precedente**: resta, con il motivo.

## 4. Che cosa non è stato toccato

**Ollama**: il backend non lo chiama; i tempi di Ollama sono quelli dei modelli nell'AI Engine, che manda già il
`keep_alive` per modello (impostazioni di esecuzione, V43). Tenere i modelli caricati più a lungo occuperebbe RAM che
su questa macchina non c'è (9–13 GB liberi): resta una scelta dell'operatore, per modello, in Modelli LLM.

## 5. Contratto

Additivo: `AgentSyncTool.checkedAt` e `refreshing`, lo stato `READING`. Proprietà `aicos.warm-up` (default `true`).

## 6. Test

`AgentSyncApiTest`: la panoramica non aspetta OpenClaw (il comando fermo su un latch: risposta subito, celle `READING`,
Open WebUI e LiteLLM non trattenuti, poi le celle vere) e una lettura vecchia mostrata subito e rinnovata dietro — rossi
prima (11 s, il tempo del latch; nessun `refreshing`). `OpenClawConnectorTest`: una lettura iniziata prima di una
pubblicazione non toglie l'agente nuovo (rosso senza il contatore). `HostSoftwareDetectorTest` (nuovo): quattro servizi
lenti chiesti insieme (2,85 s prima), una sonda non ripetuta, dove sta un comando tenuto fino ad «Aggiorna», dimenticare
i servizi tiene il resto (questi due rossi prima). Console: `AgentEcosystem.test.tsx` (la matrice subito e la lettura che arriva; rossa senza la nuova domanda),
`Integrations.test.tsx` (3: la stessa iframe dopo essere usciti e rientrati; rossi smontandola).

Suite (2026-11-12): Java **934** (0 falliti, 2 saltati: i link simbolici), console **201 su 201**, typecheck pulito.

## 7. Smoke dal vivo

Backend isolato 8099, console 5184, OpenClaw vero in sola lettura:

| | Prima | Dopo |
|---|---|---|
| `GET /api/agent-sync` subito dopo l'avvio | 28 s | **0,86 s** (OpenClaw `READING`) |
| `GET /api/agent-sync` a caldo | 0,08 s per 10 s, poi di nuovo ~30 s | **0,04–0,13 s**, sempre |
| Scheda Ecosistema, dalla navigazione alle righe | 28–60 s | **258 ms**, «Letto alle 10:21» dal riscaldamento |
| Pagina OpenClaw, 20 agenti | 12–60 s | **184 ms** |
| `GET /api/software` a caldo | 0,2–0,37 s, 2 s ogni minuto | **27–46 ms** |
| Chat LLM, uscire e rientrare | Open WebUI ricaricata | stessa iframe, nessun nuovo caricamento |

Il riscaldamento ha rilevato il catalogo in 2,2 s in background all'avvio.
