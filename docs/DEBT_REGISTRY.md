# DEBT REGISTRY — i due spazi di identificatori, disambiguati

> **Questo documento esiste per una sola ragione**: nel repository ci sono **due** registri del
> debito che usano **lo stesso formato di identificatore** (`TD-NN`) per debiti **diversi**, e
> senza una mappa una task futura può chiudere il debito sbagliato credendo di chiudere quello
> giusto.
>
> Creato da **TASK-011**, 2026-09-19. Un test (`DebtRegistryConsistencyTest`) lo tiene allineato
> all'audit: un identificatore che compare là e non qui rende rossa la suite.

## 1. Qual è autoritativo

**La numerazione viva in `.company-os/PROJECT_STATE.md` è autoritativa.** È quella che ADR,
artefatti di task e messaggi di commit citano, ed è quella che si usa per aprire un debito nuovo —
il prossimo numero libero è quello indicato in fondo alla §5.

`docs/audit/TECHNICAL_DEBT.md` è uno **snapshot datato**: la fotografia di ciò che TASK-000 trovò
nel repository iniziale. **Non va rinumerato e non va aggiornato come se fosse vivo.** Riscriverne
gli identificatori significherebbe riscrivere ciò che quella task osservò; e rinumerare quelli vivi
romperebbe ogni riferimento scritto finora, per sistemare un documento.

Perciò la collisione **non viene risolta rinumerando**: viene resa **interpretabile**. È la stessa
forma che il progetto dà alle migrazioni — non si riscrive ciò che c'è, si aggiunge ciò che manca.

**Come si cita, da oggi.** Un identificatore da solo è ambiguo solo per cinque valori, ma la
disciplina costa poco: `TD-20 (vivo)` oppure `TD-20 (audit)` quando il numero è uno dei cinque
della §2. Fuori da quei cinque, `TD-NN` significa il registro vivo.

## 2. Le cinque collisioni

Sono queste, e sono tutte. Ogni altro identificatore o coincide (§3) o esiste in un solo spazio
(§4, §5).

| ID | **Audit** (TASK-000) | **Vivo** (`PROJECT_STATE.md`) |
|---|---|---|
| **TD-14** | Build non riproducibile offline: `./mvnw -o test` fallisce per plugin assenti dalla cache. **Non rivalutato**, e resta aperto nello spazio dell'audit | **CHIUSO** il 2026-09-23. `origin` → `Gabryx82/Ai-Company-OS`, `master` pushato (`14f70b3`), workflow `CI` **registrato e attivo**, e la **run `35863517006` è passata in verde** su `ubuntu-latest` in 1m26s, eseguendo `./mvnw -B clean test` con tutti e 9 gli step `success`. Non è un file YAML: è una CI che ha girato. `tasks/TASK-012/EVIDENCE_TD14.md` §11 |
| **TD-19** | Metadati `pom.xml` vuoti | Componente del ciclo di vita del progetto. **Chiuso da TASK-004** |
| **TD-20** | Igiene Git | Le eccezioni che Spring solleva **prima** del nostro codice non entravano nel contratto d'errore. **Chiuso da TASK-005** |
| **TD-21** | `System.out.println` invece di logging | **Chiuso da TASK-005**, dentro la normalizzazione del contratto d'errore |
| **TD-22** | Configurazione porta e ambiente assenti | Mancava il test incrementale `V1 → V2`. **Chiuso da TASK-006**, con un test su **ogni** coppia consecutiva invece che su quella specifica |

**Nota importante su questi cinque.** Le voci della colonna «Audit» **non sono state rivalutate**
da TASK-011: non si sa, da questo documento, se il `pom.xml` abbia ancora i metadati vuoti. Dire
«TD-19 è chiuso» è vero nello spazio vivo e **non verificato** in quello dell'audit. È esattamente
l'equivoco che questo registro esiste per impedire.

## 3. Gli identificatori che **coincidono**

Qui i due spazi dicono la stessa cosa, ed è ciò che rende la collisione insidiosa: la maggior
parte degli identificatori si comporta bene, quindi chi ne verifica due o tre conclude che il
problema non esista.

| ID | Contenuto | Stato nel registro vivo |
|---|---|---|
| **TD-04** | Nessuna sicurezza / autenticazione | **Chiuso da TASK-013** (ADR-013): bearer token per nome su ogni `/api/**`, verificato per riflessione su tutte le rotte e per mutazione |
| **TD-07** | Nessuna gestione degli errori | **Chiuso da TASK-005** (ADR-007) |
| **TD-08** | `MasterOrchestrator` è un placeholder spacciato per AI | **Chiuso da TASK-020**: rimosso, sostituito da routing sul registro reale e dalle run di ADR-016 |
| **TD-11** | CORS incoerente | **Chiuso da TASK-014**: una policy globale, origini dichiarate, niente wildcard, `ETag` esposto |
| **TD-12** | Nessun dominio per `status` e `priority` | **Chiuso**: la metà `status` da TASK-010, la metà `priority` (TD-36) da TASK-016 |
| **TD-13** | Nessuna relazione tra entità | **Risolto da TASK-009** |
| **TD-15** | Lombok dichiarato e mai usato | **Aperto** |
| **TD-17** | `README.md` con escape markdown errati | **Chiuso** (blocco PHASE 3–7): README riscritto; `ReadmeTruthTest` fallisce se tornano |
| **TD-18** | `README.md` descrive uno stack inesistente | **Chiuso** (blocco PHASE 3–7): README descrive lo stack reale; `ReadmeTruthTest` rifiuta Supabase/Vercel/LangGraph e verifica le cartelle citate |

## 4. Solo nell'audit

Debiti che TASK-000 registrò e che la numerazione viva **non ha mai ri-assegnato**. Diversi sono
con ogni probabilità risolti — H2 è stato rimosso, Flyway possiede lo schema, la validazione
esiste, i DTO esistono, i test sono 223 — ma **TASK-011 non li ha rivalutati**, e dichiararli
chiusi senza verificarli sarebbe chiudere un debito perché il codice sembra diverso, che è
precisamente ciò che il charter vieta.

| ID | Contenuto | Stato |
|---|---|---|
| **TD-01** | Persistenza volatile (H2 in-memory) | non rivalutato qui |
| **TD-02** | Schema generato da Hibernate senza migrazioni | non rivalutato qui |
| **TD-03** | Nessuna validazione degli input | non rivalutato qui |
| **TD-05** | Entità JPA esposte come contratto API | non rivalutato qui |
| **TD-06** | Deserializzazione basata su costruttore implicito | non rivalutato qui |
| **TD-09** | Copertura di test funzionale nulla | non rivalutato qui |
| **TD-10** | Violazioni di layering nei package | non rivalutato qui |
| **TD-16** | `open-in-view` non configurato | non rivalutato qui |

**Rivalutarli è una task a sé**, e vale la pena farla: un registro di debiti in cui metà delle voci
sono risolte senza che nessuno lo dica è rumore che nasconde le voci vere. Non è stata fatta qui
perché `AUTONOMOUS_LOOP.md` §4 mette il cleanup al livello 6 e il charter §4 vieta di allargare lo
scope per sistemare tutto.

## 5. Solo nel registro vivo

Debiti aperti **dopo** l'audit, cioè dal lavoro autonomo. Nessuna ambiguità possibile: l'audit si
ferma a `TD-22`.

| ID | Contenuto | Stato | Origine |
|---|---|---|---|
| **TD-23** | L'elenco delle tabelle è dichiarato a mano, e la spiegazione diceva il contrario | chiuso | TASK-006 |
| **TD-24** | Una scrittura che funzionava solo perché il self-invocation aggirava il proxy e ignorava un `readOnly = true` | chiuso — e reso **inesprimibile**, non solo assente (AC-18) | TASK-004 |
| **TD-25** | Finestra fra il controllo «il progetto è `ACTIVE`» e il commit: lo stato poteva cambiare nel mezzo | chiuso interamente da L2+L3+L4, nessun residuo | TASK-004 |
| **TD-26** | Il path lazy non è esercitato fuori transazione | **aperto**, parzialmente coperto | TASK-004 |
| **TD-27** | Due dialetti d'errore sulla **stessa rotta**: `GET /api/projects/999/tasks` e `.../abc/tasks` rispondevano in forme diverse | chiuso | TASK-005 |
| **TD-28** | `PUT /api/projects/{id}` esposto alla sovrascrittura con dati stantii | chiuso | TASK-008 |
| **TD-29** | Forma della risposta per gli errori di locking | chiuso | TASK-005 |
| **TD-30** | Due riassegnazioni concorrenti dello stesso task: last-write-wins | chiuso | TASK-008 |
| **TD-31** | `Agent` usava un booleano dove `Project` usa un enum chiuso. Unificarli eliminava una colonna → era **hard stop #3** | **CHIUSO** 2026-09-19 da **TASK-012**: decisione umana esplicita, `V8`, ADR-012. Backfill biiettivo, contratto pubblico invariato | TASK-007 |
| **TD-32** | L'entity-tag deriva dalla versione di riga, non dai byte della rappresentazione | **aperto**, MINOR | TASK-008 |
| **TD-33** | I listati non portano ETag: mutare N risorse costa N letture | **aperto**, MINOR | TASK-008 |
| **TD-34** | Nessun modo di chiedere «i task fermi su agenti inattivi» | **aperto**, MINOR | TASK-009 |
| **TD-35** | Nessun `DELETE` dell'associazione con l'agente | **aperto**, MINOR | TASK-009 |
| **TD-36** | `Task.priority` resta una stringa libera senza vincolo DB | **chiuso** da TASK-016: `TaskPriority`, `V9`, censimento | TASK-010 |
| **TD-37** | Nessun percorso muta lo `status` di un task esistente: vocabolario chiuso, ciclo di vita non percorribile | **chiuso** da TASK-015: quattro archi, ADR-014 | TASK-010 |
| **TD-38** | Nessuna storia delle transizioni: non si sa chi ha mosso un task né quando | **aperto**, MINOR | TASK-015 |
| **TD-39** | Una run in corso non si può cancellare: si attende la fine o il timeout di lettura (10 min) | **aperto**, MINOR | TASK-019 |
| **TD-40** | Nessuna contabilità né limite di costo per le run su modelli a consumo (`anthropic`): i token sono registrati per run, mai sommati o limitati | **aperto**. Innocuo finché `ANTHROPIC_API_KEY` non è impostata; **da chiudere prima** di abilitarla in modo non presidiato | TASK-019 |

| **TD-40** *(nota PHASE 8)* | Ora vale anche per OpenRouter (`AICOS_ENGINE_OPENROUTER_API_KEY`). La scheda Consumi **somma** i token delle run a consumo per finestra, ma non **limita** nulla | **aperto** — contabilità parziale, limite assente; resta il prerequisito di PHASE 19 | PHASE 8 |
| **TD-41** | La fase di un task passa a `IN_PROGRESS`/`DONE` da handoff e review **senza lock di riga** sulla fase: un'approvazione concorrente può produrre un conflitto di `@Version` (500) invece di un 409 | **aperto**, MINOR | PHASE 11 |
| **TD-42** | Il terminale integrato nella pagina (PTY nel browser) non esiste: la scheda Terminale apre Windows Terminal nella cartella del progetto | **chiuso** in PHASE 22 (ADR-029): PTY + WebSocket + xterm.js, solo admin, ticket monouso | PHASE 8 |
| **TD-43** | Una generazione di piano non si può annullare; col 9B locale dura ~13 minuti (misurato) | **aperto**, MINOR | PHASE 10 |
| **TD-44** | Gmail, Drive, ClickUp, ChatGPT web, Gemini non sono incorporabili (policy di framing misurate): si aprono in finestre dedicate | **aperto** → PHASE 18, **decisione umana** (shell desktop, credenziali OAuth) | PHASE 8 |
| **TD-45** | Nessuna metrica di apprendimento né suggerimento automatico del livello di autonomia (ADR-022 §5) | **aperto** | PHASE 11 |
| **TD-46** | Graph engineering: il Second Brain mostra il grafo dell'ecosistema, ma non esistono ancora dependency graph e code graph del codice di un progetto | **aperto** → PHASE 16 | PHASE 14 |
| **TD-47** | I tipi della console per le risposte annidate usano `Deep<T>`: la nullabilità dei campi interni non è espressa dal contratto generato | **aperto**, MINOR | PHASE 9 |
| **TD-48** | Associare un MCP o un tool a un agente è metadato: AI Company OS non installa né configura il server MCP nello strumento che lo userà | **aperto** | PHASE 12 |
| **TD-49** | Gli handoff aprono la TUI di OpenCode; l'API locale di `opencode serve` non è ancora usata per seguire l'esecuzione | **aperto** | PHASE 11 |
| **TD-50** | Quote: Codex è misurato solo fino all'ultima sessione locale; il reset settimanale di Claude va impostato a mano; nessuna API espone le quote degli abbonamenti | **aperto**, vincolo esterno | PHASE 8 |
| **TD-51** | `llama3.2:3b` è `DEPRECATED` (sostituto `qwen3.5:4b` verificato). **Correzione (PHASE 16)**: sul DB live gli agenti del seed non avevano *alcun* modello (NULL, quindi il default dell'engine `echo`), non `llama3.2:3b`; ora il seed V2 dà loro `qwen3.5:9b` e `deepseek-coder-v2:16b` (ADR-025). Nessun agente usa più `llama3.2:3b`: rimuoverlo da Ollama (2 GB) è una scelta dell'operatore | **aperto**, decisione umana (rimozione) | PHASE 8 |
| **TD-52** | Il ruolo `CODER` è coperto da `deepseek-coder-v2:16b` (2024, senza tool calling); il candidato `qwen3-coder:30b` (19 GB) attende una decisione umana su disco e RAM | **aperto**, decisione umana | PHASE 8 |
| **TD-53** | Il token di sessione della console vive nel `sessionStorage`: uno script iniettato nella console potrebbe leggerlo. Mitigato da React (niente HTML non fidato), scadenza e revoca; un cookie `HttpOnly` richiederebbe protezione CSRF e un proxy same-origin (ADR-024 §8) | **aperto**, MINOR | PHASE 15 |

**Prossimo identificatore libero: `TD-54`.**

## 6. Come si apre un debito nuovo

1. Si usa il prossimo numero libero **della numerazione viva** (§5), mai uno dello spazio audit.
2. Lo si registra in `PROJECT_STATE.md`, nella sezione «Debito aperto rilevante», con l'id, il
   contenuto e la task che lo ha aperto.
3. Lo si aggiunge alla tabella §5 di questo documento.
4. Non si riusa mai un identificatore chiuso: un id chiuso resta chiuso, e il suo significato
   resta leggibile per chi trova un vecchio riferimento.

## 7. Che cosa questo documento **non** fa

- **Non chiude nessun debito.** Mappa e disambigua.
- **Non rinumera niente**, in nessuno dei due spazi.
- **Non fonde i due registri**: uno è la fotografia di un momento, l'altro è vivo, e fonderli
  perderebbe la data.
- **Non rivaluta lo stato dei debiti dell'audit** (§4). Quella è la task successiva più ovvia su
  questo materiale, ed è registrata come tale.
