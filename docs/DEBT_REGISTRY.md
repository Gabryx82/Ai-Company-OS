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
il prossimo numero libero è **`TD-38`**.

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
| **TD-04** | Nessuna sicurezza / autenticazione | **Aperto.** Primo candidato di PHASE 3 |
| **TD-07** | Nessuna gestione degli errori | **Chiuso da TASK-005** (ADR-007) |
| **TD-08** | `MasterOrchestrator` è un placeholder spacciato per AI | **Aperto.** Sbloccato da TASK-009, ma ancora non eseguibile: manca TD-37 |
| **TD-11** | CORS incoerente | **Aperto**, con superficie più stretta: TASK-007 ha rimosso il `@CrossOrigin` senza origine |
| **TD-12** | Nessun dominio per `status` e `priority` | **Parzialmente chiuso da TASK-010**: la metà `status`. La metà `priority` è **TD-36** |
| **TD-13** | Nessuna relazione tra entità | **Risolto da TASK-009** |
| **TD-15** | Lombok dichiarato e mai usato | **Aperto** |
| **TD-17** | `README.md` con escape markdown errati | **Aperto** |
| **TD-18** | `README.md` descrive uno stack inesistente | **Aperto** |

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
| **TD-36** | `Task.priority` resta una stringa libera senza vincolo DB | **aperto**, MINOR | TASK-010 |
| **TD-37** | Nessun percorso muta lo `status` di un task esistente: vocabolario chiuso, ciclo di vita non percorribile | **aperto** | TASK-010 |

**Prossimo identificatore libero: `TD-38`.**

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
