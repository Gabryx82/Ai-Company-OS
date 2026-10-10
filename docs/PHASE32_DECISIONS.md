# PHASE 32 — Decisioni che spettano all'operatore

> Scritto il 2026-10-08 a chiusura di PHASE 32. Ogni debito tecnico del registro che si poteva chiudere con
> il codice è chiuso, con un test visto rosso. Quelli qui sotto non si chiudono da soli: dipendono da un
> vincolo esterno (un'app o un servizio che non espone ciò che servirebbe) o da una decisione che le regole
> del progetto riservano all'operatore (cancellare dati, cambiare l'architettura contro un ADR, cambiare un
> contratto pubblicato in modo non additivo, credenziali). Per ognuno: com'è oggi, che cosa si è misurato,
> la proposta concreta e che cosa serve per farla.
>
> **Decisioni dell'operatore del 2026-10-10**: TD-51 — il modello resta; TD-67 — iGPU accesa; TD-72 — sì, unificate;
> TD-44 — resta così, i connettori li collega l'operatore. Resta in attesa di una decisione solo TD-53.

## Decisioni da prendere

### TD-51 — Rimuovere `llama3.2:3b` da Ollama (cancellazione di dati)

- **Oggi**: il modello è `DEPRECATED`, il sostituto `qwen3.5:4b` è verificato, nessun agente lo usa (seed V2).
  Occupa 2 GB.
- **Proposta**: toglierlo. Da PHASE 32 la voce si congeda anche dal catalogo (TD-71: *Modelli LLM → Congeda*,
  ripristinabile da *Impostazioni → Voci congedate*); il modello in Ollama si rimuove con
  `ollama rm llama3.2:3b`. Non si annulla: per riaverlo serve `ollama pull llama3.2:3b` (2 GB di download).
- **Serve**: il tuo sì. Poi sono due comandi, o li esegui tu.
- **Deciso il 2026-10-10: non si rimuove.** `llama3.2:3b` resta installato e nel catalogo come `DEPRECATED`. Debito chiuso
  per decisione, senza modifiche.

### TD-67 — Accendere la GPU integrata per Ollama

- **Oggi**: spenta. L'engine ora sceglie per modello quanti layer vanno sulla GPU
  (`AICOS_ENGINE_OLLAMA_NUM_GPU_BY_MODEL`, default `lfm2.5:8b=0`), quindi accenderla non rallenta LFM2.5.
- **Misurato** (PHASE 29, Arc 140V): Qwen 3.5 e Gemma 4 elaborano il prompt 2–3,7× più in fretta.
- **Proposta**: variabile d'ambiente utente `OLLAMA_IGPU_ENABLE=1`, poi riavvio di Ollama. Per tornare indietro:
  togliere la variabile e riavviare. È una configurazione di sistema del tuo PC: non la tocco io.
- **Deciso il 2026-10-10: accesa.** Variabile utente `OLLAMA_IGPU_ENABLE=1`, Ollama riavviato (da Explorer, con nessun
  modello caricato). Il log di Ollama ora elenca l'«inference compute» Vulkan dell'Arc 140V (`type=iGPU`). Verifica:
  `qwen3.5:4b` 2,9 GB tutti in GPU; `lfm2.5:8b` con `num_gpu: 0` (come lo manda l'engine) 4,9 GB tutti in CPU, 33 tok/s
  in generazione. Per spegnerla: `[Environment]::SetEnvironmentVariable('OLLAMA_IGPU_ENABLE', $null, 'User')` e
  riavvio di Ollama.

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
- **Deciso il 2026-10-10: sì, in un passo solo.** Le due anteprime rispondono un `DeletionPreview`, i due `DELETE` `204`;
  un'esecuzione in corso è un blocco dell'anteprima (`409 deletion-blocked` con la stessa frase, prima
  `409 task-run-in-progress`); `DeletionImpact` è uscito dal contratto; la console usa `ConfirmDeletion` con il nome
  digitato e, per un progetto attivo, «Archivia» accanto. Un guard del contratto tiene una sola forma (ADR-027,
  revisione TD-72).

### TD-44 — Gmail, Drive, ClickUp, ChatGPT web, Gemini dentro la console

- **Oggi**: le loro policy di framing (misurate) impediscono di incorporarli; si aprono in finestre dedicate.
- **Proposta**: per i **dati**, i loro connettori MCP ufficiali, che da PHASE 32 il Knowledge Hub installa con i
  valori richiesti (TD-89) — gli agenti li usano senza incorporare l'interfaccia; per l'**interfaccia**, restare
  alle finestre dedicate. Una shell desktop (Tauri/Electron) con webview è possibile ma è un'architettura
  nuova (impacchettamento, firma, aggiornamenti): non la propongo ora.
- **Serve**: le tue credenziali OAuth (Google Cloud, ClickUp), che inserisci tu nel dialogo d'installazione del
  connettore: AI Company OS non le conserva.
- **Deciso il 2026-10-10: resta così.** L'interfaccia nelle finestre dedicate; i connettori li colleghi tu, con le tue
  credenziali, quando vorrai (i token OAuth sono legati ad API a pagamento). Debito chiuso per decisione, senza modifiche.

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
