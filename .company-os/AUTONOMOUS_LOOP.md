# Autonomous Loop

La procedura operativa della modalità autonoma. L'autorità e i limiti stanno in
`AUTONOMOUS_CHARTER.md`; qui c'è come si lavora.

## 1. Il ciclo di una task

| # | Passo | Nota |
|---|---|---|
| 1 | Leggi **solo** il contesto minimo | `PROJECT_STATE.md`, il `CONTEXT.yaml` della task, le ADR strettamente necessarie. Nessuna scansione del repository senza una ragione dichiarata |
| 2 | Definisci il problema e gli **invarianti** | Prima del codice. Un invariante è una frase che può essere falsa: se non si può violare, non è un invariante |
| 3 | Crea o aggiorna gli artefatti | `TASK.md`, `CONTEXT.yaml`, `IMPLEMENTATION.md`, e l'ADR se c'è una decisione da non far ereditare |
| 4 | **Review critica dello scope**, prima di scrivere | Quello che si sta per costruire è la cosa giusta? Che cosa si sta assumendo? |
| 5 | Scrivi i test che **dimostrano il problema**, quando ce n'è uno | Per bug e race: prima il rosso |
| 6 | Verifica la baseline | I test nuovi devono fallire *adesso*, e per la ragione attesa. Se passano subito, è sbagliato il test |
| 7 | Implementa **il minimo** | Il minimo che rende verdi i test e veri gli invarianti. Niente di più |
| 8 | Test mirati | |
| 9 | Suite completa | |
| 10 | **Adversarial review del diff** | Sezione 2 |
| 11 | Correggi **HIGH e MEDIUM**, e ogni violazione di invariante | Senza chiedere |
| 12 | **Verifica per mutazione** i test critici | Sezione 3 |
| 13 | Registra i **LOW** e il debito che non giustifica di allargare lo scope | Si scrive, non si fa |
| 14 | Commit atomici | Uno per cambio coerente, messaggio che dice *perché* |
| 15 | Aggiorna `PROJECT_STATE.md` | Sezione 5: senza questo la task non è chiusa |
| 16 | Merge fast-forward nell'integration branch | Solo con suite verde |
| 17 | Scegli la prossima task e **ricomincia** | Senza attendere |

## 2. Adversarial review

Si legge il proprio diff cercando di **romperlo**, non di approvarlo. Le domande che pagano:

- Quale input rende falso questo invariante?
- Quale interleaving di due thread lo rende falso?
- Questo test resterebbe verde se togliessi la riga che dice di proteggere?
- Che cosa succede alla seconda chiamata? A quella concorrente? A quella su dati stantii?
- Il commento dice il vero, o dice quello che l'autore sperava?
- La motivazione scritta nell'ADR regge ancora dopo il codice, o è stata smentita da esso?

Gravità: **HIGH** = correttezza, invariante violato, perdita di dati. **MEDIUM** = un test che
non prova quello che dichiara, una motivazione falsa, un contratto non dichiarato. **LOW** =
tutto il resto. HIGH e MEDIUM si correggono nella task; i LOW si registrano.

Il rilievo più importante è quello contro il proprio ragionamento: se un'argomentazione scritta
ieri non regge oggi, si corregge il documento, non si difende.

## 3. Verifica per mutazione

Un test che non è mai stato visto fallire non prova niente. Per ogni test critico si toglie
**esattamente una cosa** — il lock, la guardia, l'ordinamento, la riga di rifiuto — e si
verifica che il test diventi rosso. Poi si rimette.

Vale in particolare per i test di concorrenza, dove il falso verde è più facile da produrre e
più difficile da notare.

## 4. Come si sceglie la prossima task

In quest'ordine. Non si scende di livello finché quello sopra è vuoto:

1. **Correttezza e invarianti già promessi.** Una cosa che il repository afferma e che non è
   vera è il lavoro più urgente che esista.
2. **Blocker architetturali.**
3. **Debito che diventa pericoloso per il passo successivo** — non il debito in generale, ma
   quello che il prossimo pezzo di lavoro toccherà.
4. **Dipendenze necessarie alla target architecture.**
5. **Funzionalità con il maggior valore strutturale** — quelle che rendono possibili le altre.
6. **Cleanup e documentazione non bloccanti.**

Non si allarga il progetto per «sistemare tutto». Un problema che non è su nessuno di questi
sei livelli si registra come debito e si lascia stare.

## 5. Che cosa deve esserci in `PROJECT_STATE.md` a fine task

Il criterio è uno: **una sessione nuova, senza cronologia, deve poter continuare.**

- branch corrente e HEAD dell'integration branch;
- task completate, con il commit che le chiude;
- task corrente o prossima, e perché è quella;
- decisioni architetturali, con lo stato di ogni ADR;
- stato della suite: quanti test, verdi o rossi;
- debito rilevante, con gli id;
- **failure ancora aperti**, se ce ne sono, detti per quello che sono;
- il prossimo passo autonomo, abbastanza concreto da essere eseguito.

## 6. Commit

Un commit per cambio coerente. Il messaggio dice **perché**, non cosa: il diff dice già cosa.
Prefissi in uso: `feat`, `fix`, `test`, `docs`, `refactor`, `chore`.

Un commit che contiene test deliberatamente rossi lo dichiara nel messaggio.
