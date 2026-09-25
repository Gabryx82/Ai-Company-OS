# Roadmap V2 — dall'operator console all'ecosistema AI Company OS

> Aperta il 2026-09-24 dalla direttiva umana di **riallineamento architetturale**. Sostituisce, per
> le fasi future, il piano pre-audit di `docs/IMPLEMENTATION_PLAN.md` e le candidate di
> `FINAL_HANDOFF.md` §8. **Non rinumera nulla**: PHASE 1–7 restano quello che sono, con i loro
> branch, piani e handoff. `ROADMAP_PHASES_3_7.md` resta come storia del blocco precedente.
>
> Delta misurato in `docs/realignment/GAP_ANALYSIS_V2.md`. Stato corrente: `PROJECT_STATE.md`.

## 0. Regole del blocco

- **Il blocco PHASE 3–7 non entra in `master`** finché non c'è una nuova Human Final Review del
  sistema riallineato. Resta su `autonomous/phase-7-operator-console`, intatto; le fasi nuove ne
  discendono in linea retta.
- Il flusso già accettato dall'operatore — progetto, task, associazione, assegnazione, Start,
  risposta del modello — è **regressione obbligatoria**: ogni fase lo riesegue (suite Java, test
  della console, e uno smoke reale alla chiusura del blocco).
- Push dei branch di sviluppo e CI autorizzati; niente force push, niente riscritture.

## 1. Le fasi

| Fase | Nome | Contenuto | Dipende da | Milestone |
|---|---|---|---|---|
| **8** | **Ecosystem catalogs** | ADR-018/019. Software Hub (catalogo, rilevamento reale, launcher ad allowlist, embed onesto), Provider & Model catalog con ruoli, provider OpenAI-compatibile nell'engine (OpenRouter, DwarfStar remoto), scheda **Consumi e quote** (Codex/Claude Code locali, run dell'engine), nuova shell della console | 7 | — |
| **9** | **Project workspace** | ADR-020. Tipologia di progetto e stack proposto, cartella di lavoro con struttura governata (`MASTER_PROMPT.md`, `docs/`, `tasks/`, `references/`, `.aicos/`), lettura/scrittura sicura dei documenti, reference visuali | 8 | — |
| **10** | **Master Orchestrator — Planning** | `plan.json` canonico, generazione da `MASTER_PROMPT.md` via engine **o** via agente esterno, import validato, `IMPLEMENTATION_PLAN.md` + `PHASE_X.md` + `TASK-XXX.md`, fasi nel DB, approvazione umana del piano e delle fasi | 9 | — |
| **11** | **Master Orchestrator — Execution** | ADR-021/022. Decisione di dispatch (agente, modello, software, contesto, prompt compatto), run dell'engine con contesto dai file, handoff a Claude Code / Codex / Antigravity / OpenCode (file + apertura nella cartella), verdetto di review, livelli HITL e Learning with Agent | 10 | **★ MU-OS** |
| **12** | **Agent ecosystem** | Prompt engineering per agente, sottoagenti, harness (direttive, skills, knowledge, MCP, tool, software), catalogo risorse ed **Explorer** | 8 | — |
| **13** | **Daily Work** | Oggi/domani/scadenze, voci personali o riferimenti a task di progetto (mai copie) | 9 | — |
| **14** | **Second Brain** | Grafo animato ed esplorabile di tutto l'ecosistema, filtri, dettaglio dei nodi | 12, 13 | — |
| **15** | **Terminal workspace** | Terminale integrato nella console (PTY: PowerShell, Claude Code, OpenCode) nella cartella del progetto | 11 | — |
| **16** | **Graph engineering & Knowledge** | Graph Engineer: dependency/code/task/agent graph generati e collegati alle task; knowledge vault per progetto | 14 | — |
| **17** | **Template / Mockup Hub** | Provider estensibili di mockup/UI kit/template, import nelle `references/` | 9 | — |
| **18** | **External integrations** | Shell desktop con WebView (Gmail, Drive, ClickUp, ChatGPT, Gemini) **se approvata**; API Gmail/Drive/ClickUp/GitHub con credenziali dell'operatore | 11 + decisione umana | — |
| **19** | **Cost governance** | TD-40: budget e limiti delle run a consumo; abilitazione presidiata di Anthropic API / OpenRouter | 8 | — |
| **20** | **3D department** | 3D Omniverse come dipartimento: progetti 3D, agente 3D, reference, launcher di Blender/Omniverse | 11, 12 | — |
| **21** | **Hardening & release** | Voice layer (opzionale), backup, packaging, review finale del prodotto completo | tutte | **★ Prodotto completo** |

**Restano 14 fasi (8–21).** Le **quattro** fasi 8–11 portano al **Minimum Usable AI Company OS**;
le fasi 12–21 portano al prodotto completo.

## 2. Minimum Usable AI Company OS (MU-OS) — fine di PHASE 11

Il punto minimo in cui l'operatore può sviluppare davvero un piccolo progetto software end-to-end.
Criterio di accettazione, tutto dal vivo sulla console:

1. **creazione progetto** con tipologia e stack proposto (PHASE 9);
2. **Master Prompt** incollato da ChatGPT Classic e salvato in `MASTER_PROMPT.md` (PHASE 9);
3. **Implementation Plan** generato dal Master Orchestrator — modello locale o agente esterno —
   con `IMPLEMENTATION_PLAN.md`, `PHASE_X.md`, `TASK-XXX.md` (PHASE 10);
4. **fasi e task** nel DB, legati ai loro file (PHASE 10);
5. **approvazione umana** del piano/fase prima che una task possa partire (PHASE 10);
6. **assegnazione dell'agente** proposta dall'orchestratore (PHASE 11);
7. **navigazione del contesto**: per ogni task, la lista dei file da leggere e un prompt compatto
   (PHASE 11);
8. **Software Hub**: i software necessari indicati e apribili nella cartella del progetto (PHASE 8+11);
9. **orchestrazione minima**: dispatch verso engine o target esterno (PHASE 11);
10. **Human-in-the-Loop** con livello di autonomia del progetto (PHASE 11);
11. **stato del progetto** visibile: fasi, task, run, review (PHASE 10–11);
12. **esecuzione reale** di almeno un agente (già vero da PHASE 6, ora con contesto dai file);
13. **review**: verdetto umano sull'esito, registrato (PHASE 11).

## 3. Perché quest'ordine

`AUTONOMOUS_LOOP.md` §4, applicato alla visione:

- **PHASE 8 prima**: la separazione dei concetti (ADR-018) è il blocker architetturale di tutto il
  resto — un orchestratore che sceglie «software» e «modello» ha bisogno che esistano come entità.
  È anche la parte più visibile e con meno dipendenze.
- **PHASE 9 → 10 → 11 in sequenza stretta**: il piano ha bisogno di una cartella dove vivere, e
  l'esecuzione ha bisogno di un piano da eseguire. È la catena del MU-OS.
- **PHASE 12–14 dopo il MU-OS**, perché arricchiscono un flusso che deve prima esistere; il Second
  Brain è l'ultima delle tre perché rappresenta entità che le altre creano.
- **PHASE 18 (integrazioni esterne) dietro una decisione umana**: credenziali OAuth e scelta della
  shell desktop non sono derivabili dal repository.
- **PHASE 19 prima di qualunque uso non presidiato di API a consumo** (TD-40, charter hard stop #6).

## 4. Git

```
master                                        checkpoint umano (d166870), NON toccato
└── autonomous/phase-7-operator-console       blocco 3–7, fermo, in attesa di review
    └── autonomous/phase-8-ecosystem-realignment   da phase-7, poi 9, 10, 11… in linea retta
```

Un integration branch per fase (`autonomous/phase-N-<nome>`) creato dal precedente; branch di task
quando una task ha più commit indipendenti. Ogni branch pushato, CI su ogni push.

## 5. Blocco di consolidamento — direttiva del 2026-09-25

Dopo la Human Final Review di PHASE 8–14 (accettate e integrate in `master` con un fast-forward il
2026-09-25), la direttiva ha chiesto UX, chiarezza dell'orchestrazione, configurabilità, integrazione
degli agenti e sicurezza **prima** delle fasi restanti. Le fasi 15–21 della tabella del §1 non erano
mai state iniziate: si rinumerano qui, in un'unica sequenza. **La tabella del §1 resta com'era, per
la storia; per PHASE ≥ 15 vale questa.**

| Fase | Contenuto | Era | ADR | Stato |
|---|---|---|---|---|
| **15** | Sicurezza: utenti, Admin, password hashate, sessioni revocabili, ruoli, registro | nuova (§8) | 024 | ✅ |
| **16** | Legame Agente → Modello → Provider → Execution Target; configurazione iniziale vs modificata; DeepSeek | nuova (§4–5) | 025 | ✅ |
| **17** | Handoff senza API OpenAI/Anthropic per ogni task: cartella, contesto, ruolo, skill, prompt | nuova (§6) | 025 | ✅ |
| **18** | UX di assegnazione COSA → A CHI → CON → ATTRAVERSO | nuova (§1) | 025 | ✅ |
| **19** | Skill e knowledge come file; Knowledge Hub riparato e rifatto | nuova (§3) | 026 | ✅ |
| **20** | Eliminazione reale di progetti e task, distinta dall'archiviazione | nuova (§2) | 027 | ✅ |
| **21** | Avvio coordinato dell'ecosistema (Ollama, Open WebUI, 3D Omniverse) | nuova (§7) | 028 | ✅ |
| **22** | Terminale integrato nella console (PowerShell, Claude Code, OpenCode) | ex 15 | 029 | ✅ |
| **23** | Graph engineering: grafo del codice di un progetto | ex 16 | — | da fare |
| **24** | Governo dei costi: prezzi, budget, blocco delle run oltre budget (TD-40) | ex 19 | — | da fare |
| **25** | Template/Mockup Hub e dipartimento 3D | ex 17, ex 20 | — | da fare |
| **26** | Integrazioni esterne con credenziali (Gmail, Drive, ClickUp) | ex 18 | — | **decisione umana** |
| **27** | Hardening, verifica end-to-end, release, Human Final Review | ex 21 | — | da fare |
