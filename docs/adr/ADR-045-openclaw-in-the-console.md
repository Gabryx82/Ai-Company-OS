# ADR-045 — OpenClaw dentro la console, e LM Studio fuori dall'ecosistema

- **Stato**: Implementata, in attesa della review umana; non committata
- **Data**: 2026-11-12
- **Decisa da**: agente, su direttiva dell'operatore del 2026-11-12 («elimina LM Studio come voce dato che non è
  installato sul pc e integra l'interfaccia di OpenClaw in AI-COMPANY-OS»)
- **Rivede**: ADR-043 §2, §4 e §7 (LM Studio esce; TD-100 si chiude senza lavoro). **Estende**: ADR-019 §6
  (che cosa sta dentro la console), ADR-043 (i comandi di OpenClaw come unica via).

## 1. Problema

1. LM Studio era nominato fra gli strumenti che ricevono gli agenti, nel Software Hub e nella sincronizzazione, ma
   sull'unica macchina dell'operatore non c'è. Una voce che non serve e un debito (TD-100) che non si sarebbe mai chiuso.
2. OpenClaw arrivava nella console solo come stato della sincronizzazione e come target di consegna. La sua interfaccia,
   la Control UI, restava fuori: per parlare a un agente o riprendere una sessione bisognava uscire da AI Company OS.

## 2. LM Studio esce

- Tolti il connettore (`LmStudioConnector`), la voce `lm-studio` del catalogo (`software.json`) e ogni menzione nella
  console. La sincronizzazione conosce tre strumenti: OpenClaw, Open WebUI, LiteLLM; `lm-studio` è ora uno strumento
  sconosciuto (`sync-tool-not-found`).
- **V46** toglie la riga `lm-studio` dai database che PHASE 37 aveva già riempito (quello dell'operatore la aveva, id
  42), **solo se nessun agente l'ha fra i suoi programmi**: in quel caso resta, e si toglie dal Software Hub. Il file
  del catalogo inserisce solo le chiavi mancanti (ADR-018 §4), quindi senza la migrazione la riga sarebbe rimasta.
  Nessun «ritiro» (`catalog_retirements`): la voce non è più spedita, e ripristinarla non avrebbe da dove prenderla.
- **TD-100 chiuso**: non c'è più niente da scrivere. Se un giorno LM Studio torna, si riparte da ADR-043 §7.
- V44 nomina ancora LM Studio nel suo commento: una migrazione applicata non si modifica (cambierebbe il checksum).

## 3. Perché la Control UI non sta in un riquadro

La console incorpora in un riquadro (iframe) i servizi locali che lo permettono, come Open WebUI (ADR-019 §6). Misurato
sul gateway dell'operatore (OpenClaw 2026.11.2, `http://127.0.0.1:18789/`), la Control UI risponde con:

```
X-Frame-Options: DENY
Content-Security-Policy: … frame-ancestors 'none'; …
```

e la documentazione di OpenClaw dice che quella politica «è sempre attiva e non configurabile». L'unica deroga è
l'ingresso remoto per plugin ufficiali su origini HTTPS (`plugin-sdk/gateway-ingress`), che non è il caso di una console
locale. La Control UI è una superficie di amministrazione (chat, configurazione, approvazioni di esecuzione): rifiutare
di stare in una pagina altrui è la sua difesa dal clickjacking.

**Scartato**: un proxy che tolga quelle intestazioni e inoltri il WebSocket. Aggirerebbe di proposito una difesa del
programma, costringerebbe ad aggiungere l'origine della console agli `allowedOrigins` del gateway e a far passare dal
control plane il token o l'accoppiamento del browser. Non si fa.

## 4. Decisione: una pagina «OpenClaw» nativa

Nella barra laterale, accanto a «Chat LLM», e come tessera in **Integrazioni**. Mostra ciò che l'operatore usa ogni
giorno, letto con i comandi di OpenClaw come la sincronizzazione (ADR-043):

| Nella pagina | Come | Chi |
|---|---|---|
| Stato del gateway e indirizzo; «Avvia il gateway» quando è spento | `/healthz` (senza credenziali); il Software Hub avvia `openclaw-gateway` | chiunque; avvio: admin |
| Gli agenti, il predefinito per primo, con il legame all'agente di AI Company OS | `openclaw agents list --json` e `agent_publications` | chiunque |
| Una conversazione con l'agente scelto | `openclaw agent --agent <id> --session-key agent:<id>:aicos-console --message-file <file> --json` | admin |
| Le sessioni recenti di tutti gli agenti, quelle delle task con il loro numero | `openclaw sessions --all-agents --json --limit 40` | chiunque |
| «Riprendi» una sessione, «Continua nel terminale» la conversazione | `openclaw tui --session <chiave>` in Windows Terminal (il launcher, ADR-019) | admin |
| «Apri la Control UI» per tutto il resto (impostazioni, automazioni, canali) | `openclaw dashboard` | admin |

Rotte: `GET /api/openclaw[?refresh=true]`, `POST /api/openclaw/control-ui`, `POST /api/openclaw/terminal`,
`POST /api/openclaw/messages`, `GET /api/openclaw/messages[/{id}]`. Ogni `POST` è dell'amministratore: un messaggio
fa lavorare un agente con gli strumenti che OpenClaw gli dà, e terminale e browser si aprono su questa macchina.

## 5. Invarianti

1. **Il token del gateway non passa di qui.** La Control UI la apre `openclaw dashboard`: OpenClaw emette un
   accoppiamento monouso per il browser e lo apre da sé; senza terminale non chiede nulla e non stampa il link con
   l'accoppiamento. Se l'apertura fallisce, l'esito riporta l'output **senza** ciò che segue il percorso di un link
   (`#…`, `?…`), dove starebbe un accoppiamento.
2. **Nessuna richiesta aspetta un comando di OpenClaw.** Sulla macchina dell'operatore un comando che parla con il
   gateway impiega 30–60 s anche con il gateway acceso (misurato il 2026-11-12: `agents list` 58 s, `sessions` 57 s,
   `health` 31 s). La lettura di agenti e sessioni gira in background (i due comandi insieme), si tiene 30 s e la pagina
   mostra l'ultima con l'ora; la risposta di un agente si raccoglie in background e la pagina la chiede ogni 3 s.
3. **Il messaggio va in un file.** `--message-file`, mai come argomento: una riga di comando non porta ogni carattere
   che l'operatore scrive, e il file si cancella dopo.
4. **Un messaggio alla volta per agente**, come OpenClaw esegue un turno alla volta per sessione: il secondo è rifiutato
   finché il primo non ha risposta.
5. **Una chiave di sessione che Windows Terminal leggerebbe come sintassi non si apre e non si mostra**
   (`^[A-Za-z0-9][A-Za-z0-9:._@+-]{0,199}$`), in aggiunta al controllo del launcher (ADR-019 I3).
6. **Gateway spento = risposta subito**, senza eseguire `openclaw` (la sonda di ADR-043 §9).
7. **La conversazione resta in OpenClaw.** La console tiene gli ultimi 100 messaggi finché il backend non si riavvia;
   la sessione `aicos-console` di ogni agente è di OpenClaw, e si continua nel terminale o nella Control UI.

## 6. Contratto e schema

Additivo: le rotte di §4, il problema `openclaw-item-not-found` (404: un agente che OpenClaw non ha, un messaggio mai
inviato); OpenClaw non disponibile è `sync-tool-unavailable` (409), un rifiuto `sync-refused` (502). Migrazione V46,
solo dati. `aicos.openclaw.background` (default `true`; nei test `false`, perché le letture avvengano nella richiesta).

## 7. Test

`OpenClawConsoleApiTest` (12): lettura e legami, lettura tenuta 30 s, gateway spento senza comandi, lettura fallita che
tiene la precedente, messaggio in file nella sessione della console e risposta letta, turno fallito con il motivo, un
messaggio alla volta, solo agenti che OpenClaw ha, Control UI aperta da OpenClaw, Control UI non aperta senza ripetere
l'accoppiamento, sessione nel terminale (e chiave ostile rifiutata), permessi. `MigrationStreamTest` (V46, con e senza
un agente che usa LM Studio), `AgentSyncApiTest` aggiornato (tre strumenti; `lm-studio` sconosciuto). Console:
`OpenClaw.test.tsx` (5).

Nella suite intera della console un test non toccato (`OrchestratorPanel`, la consegna a Claude Code app) è fallito
una volta per un `findByRole` oltre il limite di 1 s di Testing Library, verde da solo e al giro dopo: il limite delle
attese è ora 5 s per tutta la suite (`src/test/setup.ts`), per la stessa ragione di `testTimeout` (TD-99).

Mutazioni viste rosse (6): la redazione del link, un messaggio alla volta, la lettura tenuta, la regola admin, la
sessione della console, la sonda del gateway prima dei comandi; più V46 visto rosso prima di scriverlo. Nella console
(2): il messaggio scrivibile da un operatore, e la risposta chiesta di nuovo quando alla prima domanda l'agente sta
ancora lavorando.

Suite (2026-11-12): Java **927** (0 falliti, 2 saltati), console **197 su 197**, typecheck pulito.

Smoke dal vivo isolato (backend 8099, console 5184, database `aicompany_p45`, launcher spento), con l'OpenClaw vero in
sola lettura: la pagina risponde subito e mostra la lettura in corso; in circa un minuto compaiono i 20 agenti reali e
le 2 sessioni reali con modello ed etichetta; nessuno sforamento a 375 px.

**Non verificato dal vivo**: una conversazione vera con un agente (userebbe il tuo abbonamento), l'apertura della
Control UI da `openclaw dashboard` (accoppierebbe un browser come amministratore), l'apertura di `openclaw tui`.
