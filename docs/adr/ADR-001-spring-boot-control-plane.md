# ADR-001 — Spring Boot resta il control plane; il livello AI sarà un servizio Python separato

- **Stato**: Accettata
- **Data**: 2026-09-11
- **Decisa da**: utente (approvazione esplicita all'apertura di TASK-001)
- **Contesto**: TASK-000 (`docs/audit/MIGRATION_MAP.md` §5), review Codex (`docs/audit/CODEX_REVIEW.md` §ADR-001)

## Contesto

`docs/MASTER_PROMPT.md` indicava FastAPI come backend preferito, esplicitamente "subject to TASK-000 validation". L'audit ha però trovato un backend Spring Boot 4.1 / Java 21 funzionante: build verde, test verdi, endpoint funzionanti. È l'unico codice applicativo del repository.

La review Codex ha dato parere favorevole condizionato, osservando correttamente che l'argomento "distrugge l'unico asset" è debole di per sé — circa 350 righe sono un costo irrecuperabile modesto — e che la decisione va motivata su familiarità del manutentore, costo di due runtime, debug distribuito e necessità concreta dell'ecosistema Python.

## Decisione

1. **Spring Boot / Java 21 resta il control plane principale**: progetti, definizioni di agent e task, autorizzazioni, approvazioni, stato canonico delle esecuzioni.
2. **Il livello AI sarà un servizio Python separato**: chiamate ai provider, adattatori dei modelli, eventuale runtime a grafo ed embedding.
3. **Il servizio Python non viene implementato ora.** La sua introduzione è rinviata alla fase M-5 e richiederà una decisione propria.

## Motivazione

- Il backend esistente è funzionante e verificato: sostituirlo comporterebbe un costo certo per un beneficio non dimostrato.
- Java 21 e Spring Boot offrono già persistenza transazionale, validazione e sicurezza maturi — esattamente ciò che serve a un control plane che custodisce lo stato canonico.
- L'ecosistema AI è prevalentemente Python: forzarlo in Java sarebbe un attrito reale. Separare i due runtime lascia ciascun linguaggio dove è più forte.
- Lo stack poliglotta era già l'intento originale dichiarato nel `README.md` del progetto.

## Conseguenze

**Positive**
- Nessuna riscrittura; il lavoro prosegue per estensione.
- Confine esplicito tra stato canonico e runtime di inferenza.

**Negative, accettate**
- Due runtime da installare, avviare, osservare e distribuire.
- Debug distribuito attraverso un confine HTTP.
- Rischio di logica duplicata (routing, quota, retry) se il confine non viene mantenuto rigoroso.

## Confine di proprietà dei dati

Ripreso dalla review Codex e adottato come vincolo:

| Responsabilità | Proprietario |
|---|---|
| Progetti, definizioni agent/task, autorizzazioni, approvazioni, stato canonico delle run | Spring Boot |
| Chiamate ai provider, adattatori modelli, eventuale runtime a grafo/embedding | Servizio Python |
| Tabelle di dominio | Scritture **solo** attraverso Spring; nessuna scrittura Python indipendente sulle stesse tabelle |
| Stato/checkpoint interni del runtime AI | Storage di proprietà del runtime, referenziato per ID |

Il contratto fra i due servizi dovrà essere HTTP versionato, con DTO espliciti, ID di correlazione, timeout, errori e autenticazione fra servizi.

## Rinviato

La scelta del runtime AI concreto (LangGraph o alternative) **non** è decisa qui. Sarà validata in M-5 con un esperimento limitato e criteri misurabili.
