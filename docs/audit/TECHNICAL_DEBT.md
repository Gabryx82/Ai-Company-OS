# TECHNICAL_DEBT — Debito tecnico e rischi (TASK-000)

> ## ⚠️ Gli identificatori di questo documento non sono quelli vivi
>
> Questo file è uno **snapshot datato**: la fotografia che TASK-000 scattò del repository
> iniziale. I suoi `TD-NN` appartengono a **quello** spazio di identificatori.
>
> La numerazione **viva** — quella che ADR, artefatti di task e messaggi di commit citano — sta
> in `.company-os/PROJECT_STATE.md`, ed è **autoritativa**. Le due si sovrappongono, e **cinque
> identificatori significano cose diverse nei due spazi**:
>
> | ID | Qui significa | Nel registro vivo significa |
> |---|---|---|
> | `TD-14` | Build non riproducibile offline | Nessuna CI |
> | `TD-19` | Metadati `pom.xml` vuoti | Componente del ciclo di vita (chiuso) |
> | `TD-20` | Igiene Git | Eccezioni sollevate da Spring prima del nostro codice (chiuso) |
> | `TD-21` | `System.out.println` invece di logging | (chiuso da TASK-005) |
> | `TD-22` | Configurazione porta e ambiente assenti | Test incrementale `V1 → V2` (chiuso) |
>
> **Prima di chiudere o citare un `TD-NN`, leggere `docs/DEBT_REGISTRY.md`**, che mappa i due
> spazi per intero. Senza quella mappa è possibile chiudere il debito sbagliato credendo di
> chiudere quello giusto.
>
> **Questo file non va rinumerato**: riscriverne gli identificatori significherebbe riscrivere
> ciò che TASK-000 osservò.

Severità: **CRITICO** (blocca l'evoluzione o causa perdita dati) · **ALTO** (va risolto in Fase 1) · **MEDIO** (va risolto prima della Fase 3) · **BASSO** (igiene).

## 1. CRITICO

### TD-01 — Persistenza volatile (H2 in-memory)
- **Dove**: `application.properties` → `jdbc:h2:mem:aicompany`
- **Impatto**: ogni riavvio distrugge tutti i dati. Nessuna funzionalità che richieda stato durevole (progetti, memoria, artefatti, knowledge graph) è realizzabile finché resta così.
- **Rischio**: qualunque lavoro costruito sopra questa base va rifatto quando si passerà a PostgreSQL.
- **Azione**: PostgreSQL con profili; H2 relegato ai soli test.

### TD-02 — Schema generato da Hibernate senza migrazioni
- **Dove**: `spring.jpa.hibernate.ddl-auto=update`
- **Impatto**: nessuna storia dello schema, nessun rollback, nessun controllo su indici e vincoli. `update` non rimuove mai colonne né rileva conflitti: causa drift silenzioso tra ambienti.
- **Rischio**: in produzione, corruzione o perdita di dati.
- **Azione**: Flyway con baseline; `ddl-auto=validate`.

### TD-03 — Nessuna validazione degli input
- **Dove**: `TaskController.createTask`, `Task`, `Agent`
- **Evidenza**: `POST /api/tasks` con `{}` → `200` e riga con tutti i campi null.
- **Impatto**: dati corrotti persistiti senza resistenza. `spring-boot-starter-validation` è dichiarato ma mai usato.
- **Azione**: DTO con vincoli Jakarta Validation, `@Valid` sui controller, vincoli `NOT NULL` a livello DB.

### TD-04 — Nessuna sicurezza
- **Impatto**: tutti gli endpoint sono anonimi e pubblici. Non esistono autenticazione, autorizzazione, RBAC, rate limiting o audit trail.
- **Rischio**: il Product Vision prevede credenziali di provider AI, integrazioni Gmail/Drive/GitHub e dati di pagamento. Introdurre la sicurezza **dopo** aver costruito questi moduli è enormemente più costoso e rischioso.
- **Azione**: Spring Security va pianificato in Fase 1–2, non rimandato.

## 2. ALTO

### TD-05 — Entità JPA esposte come contratto API
- **Dove**: tutti e tre i controller.
- **Impatto**: il modello di persistenza è il contratto pubblico. Qualsiasi refactoring del DB rompe i client; qualsiasi campo interno (o futuro segreto, es. API key di un agent) viene serializzato per default.
- **Azione**: livello DTO + mapper esplicito.

### TD-06 — Deserializzazione basata su costruttore implicito
- **Dove**: `Task`, `Agent` — solo getter, nessun setter.
- **Evidenza**: `POST /api/tasks` funziona perché Jackson 3 usa il costruttore a 4 argomenti come creator implicito.
- **Impatto**: comportamento non dichiarato e fragile. Aggiungere o riordinare un parametro del costruttore rompe l'API **senza errori di compilazione**. L'assenza di setter blocca inoltre qualsiasi operazione di update.
- **Azione**: DTO espliciti (record) per l'input; entità con setter o builder per la mutazione.

### TD-07 — Nessuna gestione degli errori
- **Impatto**: stacktrace e formato di default di Spring esposti al client. Nessun contratto d'errore stabile per il frontend.
- **Azione**: `@RestControllerAdvice` con formato standard (es. RFC 7807 Problem Details, supportato nativamente da Spring).

### TD-08 — `MasterOrchestrator` è un placeholder spacciato per AI
- **Dove**: `ai/orchestrator/model/MasterOrchestrator.java`
- **Impatto**: 4 `if` su `contains()`. Restituisce nomi di agent (`"Backend Agent"`, `"Marketing Agent"`) **che non esistono nel database**: il "sistema multi-agente" non è collegato al proprio registry di agent.
- **Rischio principale**: dà l'illusione che l'orchestrazione esista. Ogni stima e ogni piano che assumano "l'orchestratore c'è già" saranno sbagliati.
- **Azione**: REPLACE. Nessun tentativo di evolvere questo codice.

### TD-09 — Copertura di test funzionale nulla
- **Impatto**: 1 solo test (`contextLoads`). Nessuna rete di sicurezza per il refactoring massiccio che le fasi successive richiedono. I 3 starter di test dichiarati sono inutilizzati.
- **Azione**: test di controller (`@WebMvcTest`), di repository (`@DataJpaTest`) e di integrazione (Testcontainers) prima di iniziare il refactoring di dominio.

## 3. MEDIO

### TD-10 — Violazioni di layering nei package
- `AgentInitializer` (un `CommandLineRunner`) è in `agent/repository/`.
- `MasterOrchestrator` (un `@Service`) è in `ai/orchestrator/model/`.
- **Impatto**: la struttura package-by-feature perde valore se le convenzioni non sono rispettate; con 25 domini in arrivo, il disordine si moltiplica.

### TD-11 — CORS incoerente
`@CrossOrigin` senza origini solo su `AgentController`. Nessuna configurazione globale. Il frontend futuro incontrerà comportamenti diversi per endpoint.

### TD-12 — Nessun dominio per `status` e `priority`
Stringhe libere, nessun enum, nessuna macchina a stati. Il Task Engine del Product Vision richiede transizioni controllate e gate di approvazione: impossibili su stringhe arbitrarie.

### TD-13 — Nessuna relazione tra entità
`Agent` e `Task` non si conoscono. Non esiste `Project`. Il modello di dominio è un insieme di tabelle scollegate, non un dominio.

### TD-14 — Build non riproducibile offline
`./mvnw -o test` fallisce per plugin Maven assenti dalla cache. Nessun Docker, nessuna CI: non esiste un ambiente di build garantito.

### TD-15 — Lombok dichiarato e mai usato
Dipendenza + configurazione dell'annotation processor in `pom.xml`, zero utilizzi nel codice. Decidere: adottarlo o rimuoverlo.

### TD-16 — `open-in-view` non configurato
Warning esplicito nei log di build. Abilitato per default: rischio di query lazy durante la serializzazione.

## 4. BASSO

### TD-17 — `README.md` con escape markdown errati
`\#`, `\-`, `\## Stack`: il file non renderizza come markdown ed è il primo documento che chiunque legge.

### TD-18 — `README.md` descrive uno stack inesistente
Dichiara React, Python/LangGraph e Supabase: nessuno dei tre esiste. Documentazione che descrive desideri come realtà.

### TD-19 — Metadati `pom.xml` vuoti
`<name/>`, `<description/>`, `<url/>`, `<licenses><license/></licenses>`, `<developers><developer/></developers>` con elementi vuoti.

### TD-20 — Igiene Git
- Nessun `.gitignore` alla radice.
- Nessun remote configurato (**nessun backup remoto del lavoro**).
- Un solo commit; tutto il codice applicativo è **staged ma non committato**.
- `backend/HELP.md` è simultaneamente in `.gitignore` e nell'index.

### TD-21 — `System.out.println` invece di logging
`AgentInitializer` usa `System.out`. Nessuna strategia di logging strutturato.

### TD-22 — Configurazione porta e ambiente assenti
Nessun `server.port`, nessuna gestione di variabili d'ambiente o segreti — necessaria appena si introdurranno chiavi API di provider AI.

## 5. I tre rischi architetturali più pericolosi

1. **Costruire i moduli AI prima di aver fissato persistenza e dominio** (TD-01 + TD-02 + TD-13). Ogni modulo costruito su H2 volatile e su entità scollegate andrà rifatto. È il rischio più costoso ed è quello più facile da innescare, perché i moduli AI sono la parte "interessante".

2. **Rimandare la sicurezza** (TD-04). Il vision include credenziali di provider, OAuth Google, GitHub e pagamenti. Aggiungere l'autenticazione a 25 domini già scritti è un progetto a sé.

3. **Scambiare l'ampiezza dello scope per progresso**. `ARCHITECTURE.md` elenca 25 domini; ne esiste il ~2%. Il pericolo concreto non è tecnico ma di pianificazione: aprire molti domini in superficie invece di completarne pochi in profondità produce 25 stub e zero funzionalità utilizzabili — cioè esattamente ciò che `MasterOrchestrator` è oggi, moltiplicato per 25.
