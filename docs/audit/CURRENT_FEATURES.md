# CURRENT_FEATURES — Funzionalità realmente disponibili (TASK-000)

Ogni voce è stata **verificata a runtime** avviando l'applicazione (`./mvnw spring-boot:run`) e interrogando gli endpoint, non dedotta dal codice.

## 1. Funzionalità funzionanti

### F-01 — Elenco agent
- **Endpoint**: `GET /api/agents`
- **Stato**: ✅ funzionante
- **Verifica**: `200` con 3 agent seed.
```json
[{"name":"Code Architect","role":"Software Engineer","specialization":"Backend architecture and system design","active":true,"id":1},
 {"name":"Frontend Developer","role":"Frontend Engineer","specialization":"React TypeScript UI development","active":true,"id":2},
 {"name":"Database Specialist","role":"Database Engineer","specialization":"PostgreSQL and data modeling","active":true,"id":3}]
```
- **Limiti**: sola lettura. Nessun `GET /{id}`, `POST`, `PUT`, `DELETE`. Nessuna paginazione, nessun filtro (nemmeno per `active`).

### F-02 — Seed automatico degli agent
- **Componente**: `AgentInitializer` (`CommandLineRunner`)
- **Stato**: ✅ funzionante — log `AI COMPANY OS: Agents initialized` osservato all'avvio.
- **Limiti**: dati hardcoded in Java, idempotente solo tramite `count()==0`. Su H2 in-memory il seed rigira a ogni avvio. Usa `System.out.println` invece di un logger.

### F-03 — Creazione task
- **Endpoint**: `POST /api/tasks`
- **Stato**: ✅ funzionante
- **Verifica**: payload `{"title":"T1","description":"D1","status":"OPEN","priority":"HIGH"}` → `200` con `{"...","id":1}`.
- **Limiti**: ritorna `200` invece di `201 Created`, nessun header `Location`, nessuna validazione (vedi D-01).

### F-04 — Elenco task
- **Endpoint**: `GET /api/tasks`
- **Stato**: ✅ funzionante
- **Limiti**: nessuna paginazione, ordinamento, filtro per stato o assegnazione.

### F-05 — "Orchestrazione" per keyword
- **Endpoint**: `POST /api/orchestrator`
- **Stato**: ⚠️ funzionante ma **non è AI**
- **Verifica**: body `build me an ecommerce` → `200` `"Backend Agent + Database Agent"`.
- **Comportamento reale**: 4 `if` su `String.contains()` (`ecommerce`, `cms`, `marketing`, `social`), default `"General AI Agent"`. Ritorna una stringa libera, non un riferimento a entità `Agent` esistenti: i nomi restituiti (`"Backend Agent"`, `"Marketing Agent"`) **non corrispondono ad alcun agent nel database**.

### F-06 — Persistenza JPA
- **Stato**: ✅ funzionante ma volatile
- H2 in-memory, schema creato da Hibernate a ogni avvio. Nessun dato sopravvive al riavvio.

## 2. Funzionalità incomplete o difettose

### D-01 — Nessuna validazione degli input
- **Verifica**: `POST /api/tasks` con body `{}` → `200`, riga persistita con **tutti i campi a null** (`{"title":null,"description":null,"status":null,"priority":null,"id":2}`).
- `spring-boot-starter-validation` è nel `pom.xml` ma **non viene usato**: nessun `@Valid`, nessun vincolo (`@NotBlank`, `@Size`) su alcuna entità o DTO.

### D-02 — `status` e `priority` senza dominio
Stringhe libere: `"OPEN"`, `"open"`, `"banana"` sono tutti accettati. Nessun enum, nessuna macchina a stati, nessun vincolo DB.

### D-03 — Console H2 configurata ma non attiva
`spring.h2.console.enabled=true` è presente, ma `GET /h2-console` risponde **404** (verificato). La proprietà è inerte in questa configurazione di Spring Boot 4.

### D-04 — CORS parziale
`@CrossOrigin` solo su `AgentController`, senza origini dichiarate. `TaskController` e `OrchestratorController` non hanno alcuna policy. Un frontend potrà leggere gli agent ma non necessariamente creare task.

### D-05 — Nessuna gestione errori
Nessun `@ControllerAdvice`. Ogni eccezione risale come stacktrace nel formato di default di Spring. Nessun contratto di errore per il client.

### D-06 — Contratto orchestrator non strutturato
`POST /api/orchestrator` accetta e ritorna `String` grezzo. Non è consumabile in modo affidabile da un frontend tipizzato.

### D-07 — Build non riproducibile offline
`./mvnw -o test` fallisce: plugin Maven mancanti nella cache locale. Serve rete per una build pulita.

## 3. Copertura di test

| Test | Copertura |
|---|---|
| `BackendApplicationTests.contextLoads` | verifica solo che il contesto Spring si avvii |

**Nessun test** su controller, service, repository, serializzazione, o sulla logica di `MasterOrchestrator`. Copertura funzionale effettiva: **0%**.

I tre starter di test dichiarati (`data-jpa-test`, `validation-test`, `webmvc-test`) sono **inutilizzati**.

## 4. Funzionalità dichiarate ma inesistenti

Il `README.md` dichiara "Sistema multi-agente AI con Human in the Loop". Nella realtà del codice:

| Dichiarato | Realtà |
|---|---|
| Sistema multi-agente | Tabella anagrafica di 3 righe, nessuna esecuzione |
| AI | Nessuna chiamata a modelli, nessuna dipendenza AI |
| Human in the Loop | Nessun gate di approvazione nel codice (esiste solo come processo in `.company-os/`) |
| Frontend React/TS | Assente |
| Python + LangGraph | Assente |
| Supabase | Assente (H2 in-memory) |
