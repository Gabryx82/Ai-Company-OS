# PHASE 32 — Decisioni che spettano all'operatore

> Scritto il 2026-10-08 a chiusura di PHASE 32. Ogni debito tecnico del registro che si poteva chiudere con
> il codice è chiuso, con un test visto rosso. Quelli qui sotto non si chiudono da soli: dipendono da un
> vincolo esterno (un'app o un servizio che non espone ciò che servirebbe) o da una decisione che le regole
> del progetto riservano all'operatore (cancellare dati, cambiare l'architettura contro un ADR, cambiare un
> contratto pubblicato in modo non additivo, credenziali). Per ognuno: com'è oggi, che cosa si è misurato,
> la proposta concreta e che cosa serve per farla.

## Decisioni da prendere

### TD-51 — Rimuovere `llama3.2:3b` da Ollama (cancellazione di dati)

- **Oggi**: il modello è `DEPRECATED`, il sostituto `qwen3.5:4b` è verificato, nessun agente lo usa (seed V2).
  Occupa 2 GB.
- **Proposta**: toglierlo. Da PHASE 32 la voce si congeda anche dal catalogo (TD-71: *Modelli LLM → Congeda*,
  ripristinabile da *Impostazioni → Voci congedate*); il modello in Ollama si rimuove con
  `ollama rm llama3.2:3b`. Non si annulla: per riaverlo serve `ollama pull llama3.2:3b` (2 GB di download).
- **Serve**: il tuo sì. Poi sono due comandi, o li esegui tu.

### TD-67 — Accendere la GPU integrata per Ollama

- **Oggi**: spenta. L'engine ora sceglie per modello quanti layer vanno sulla GPU
  (`AICOS_ENGINE_OLLAMA_NUM_GPU_BY_MODEL`, default `lfm2.5:8b=0`), quindi accenderla non rallenta LFM2.5.
- **Misurato** (PHASE 29, Arc 140V): Qwen 3.5 e Gemma 4 elaborano il prompt 2–3,7× più in fretta.
- **Proposta**: variabile d'ambiente utente `OLLAMA_IGPU_ENABLE=1`, poi riavvio di Ollama. Per tornare indietro:
  togliere la variabile e riavviare. È una configurazione di sistema del tuo PC: non la tocco io.

### TD-53 — Token di sessione in un cookie `HttpOnly` (architettura, ADR-024 §8)

- **Oggi**: il token vive nel `sessionStorage`; mitigato da React (niente HTML non fidato), scadenza, revoca.
- **Proposta** (una fase): la console servita dallo stesso origin del backend (in produzione dalle risorse
  statiche di Spring, in sviluppo con il proxy di Vite); `POST /api/auth/login` imposta un cookie
  `aicos_session` `HttpOnly`, `SameSite=Strict`, `Path=/api`; ogni richiesta che modifica porta un token CSRF
  (double submit: cookie leggibile + intestazione `X-AICOS-CSRF`); i token di servizio restano `Bearer`. La console
  smette di vedere il token.
- **Perché è una decisione**: cambia l'architettura scritta in ADR-024 §8 e il contratto di login per la console
  (oggi restituisce il token).
- **Serve**: il tuo via libera per una revisione di ADR-024.

### TD-72 — Una sola forma di eliminazione (contratto non additivo)

- **Oggi**: progetti e task (ADR-027) rispondono `200` con `DeletionImpact` e chiedono il nome digitato; le
  eliminazioni di PHASE 30 rispondono `204` con `DeletionPreview` e `ConfirmDeletion`.
- **Proposta** in due passi: (1) additivo — `GET /api/projects/{id}/deletion` e `GET /api/tasks/{id}/deletion`
  accettano `?shape=preview` e restituiscono un `DeletionPreview`; la console usa `ConfirmDeletion` con
  `typedName` (che già esiste) anche per progetti e task; (2) non additivo — alla fase successiva
  `DELETE /api/projects/{id}` e `DELETE /api/tasks/{id}` rispondono `204` come gli altri, e `DeletionImpact` esce
  dal contratto.
- **Serve**: il tuo sì al passo (2): un client esterno che legge oggi il corpo del `200` andrebbe aggiornato.

### TD-44 — Gmail, Drive, ClickUp, ChatGPT web, Gemini dentro la console

- **Oggi**: le loro policy di framing (misurate) impediscono di incorporarli; si aprono in finestre dedicate.
- **Proposta**: per i **dati**, i loro connettori MCP ufficiali, che da PHASE 32 il Knowledge Hub installa con i
  valori richiesti (TD-89) — gli agenti li usano senza incorporare l'interfaccia; per l'**interfaccia**, restare
  alle finestre dedicate. Una shell desktop (Tauri/Electron) con webview è possibile ma è un'architettura
  nuova (impacchettamento, firma, aggiornamenti): non la propongo ora.
- **Serve**: le tue credenziali OAuth (Google Cloud, ClickUp), che inserisci tu nel dialogo d'installazione del
  connettore: AI Company OS non le conserva.

## Vincoli esterni (nessuna azione possibile oggi; si riverifica)

| Debito | Che cosa manca, fuori da AI Company OS | Verifica fatta il 2026-10-08 | Quando riaprirlo |
|---|---|---|---|
| TD-50 | Nessuna API espone le quote degli abbonamenti (Claude, ChatGPT/Codex) | Codex resta misurato fino all'ultima sessione locale; il reset settimanale di Claude si imposta una volta in *Utilizzo* | Quando Anthropic o OpenAI pubblicano un endpoint di utilizzo per gli abbonamenti |
| TD-74 | Codex non dice la versione più recente di un plugin installato | `codex plugin list --json --available`: 24 installati con la loro versione, 5575 *non* installati con la loro; nessuna versione «disponibile» per gli installati. Codex li aggiorna da sé | Quando la CLI di Codex espone un `outdated` o la versione del marketplace per gli installati |
| TD-83 | I connettori aggiunti su claude.ai non sono salvati sul PC | Solo i nomi in `claudeAiMcpEverConnected` (`~/.claude.json`) | Se Claude Code/Desktop li salvano in locale |
| TD-86 | Claude Desktop riceve plugin e connettori remoti dall'account | Il Hub dice dove aggiungerli e apre l'app; installa i connettori locali in `claude_desktop_config.json` | Se Claude Desktop espone una CLI o un file per i plugin |
| TD-87 | ChatGPT (chat) non tiene skill né app sul PC | Le app dell'account si vedono solo dalla cache di Codex | Se ChatGPT desktop salva in locale skill o app |

Ognuno resta nel registro con stato «vincolo esterno» e la data di questa verifica: non è chiuso, perché la
cosa che servirebbe non esiste ancora.
