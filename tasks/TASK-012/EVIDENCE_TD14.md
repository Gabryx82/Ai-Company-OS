# TD-14 — Evidenza raccolta, e il dato che manca

Raccolta il 2026-09-19 prima di qualunque configurazione, perché la regola era «non inventare
owner, URL o repository remoto». Ogni riga è riproducibile con il comando accanto.

## 1. Stato reale di Git

| Domanda | Risposta | Comando |
|---|---|---|
| Remote configurati? | **Nessuno** | `git remote -v` → vuoto |
| `.git/config` contiene una sezione `[remote ...]`? | **No.** Contiene solo `[core]` | `cat .git/config` |
| Sono mai esistiti ref remoti? | **No.** `.git/refs/remotes` non esiste, e `packed-refs` contiene solo `refs/heads/*` e un ref interno di Codex | `ls .git/refs/remotes` |
| È mai stato fatto un fetch? | **No.** `.git/FETCH_HEAD` non esiste | `ls .git/FETCH_HEAD` |

`.git/config`, per intero:

```
[core]
	repositoryformatversion = 0
	filemode = false
	bare = false
	logallrefupdates = true
	symlinks = false
	ignorecase = true
```

**Nessun remote è mai stato configurato in questo repository.**

## 2. Riferimenti documentati a un repository remoto

Ricerca su **tutti** i file tracciati:

```bash
git grep -niE "github\.com|gitlab\.com|bitbucket|git@|https://[a-z0-9.-]+/[^/]+/[^/]+\.git|origin"
```

**Nessun risultato utile.** Tutte le occorrenze di `origin` sono falsi positivi in due famiglie:

- la parola italiana **«origine»** nella prosa su CORS e sul protocollo di lock — `ADR-006`,
  `ADR-010`, `AgentController`, `PROJECT_STATE.md` («progetto di origine», «qualunque origine»);
- l'identificatore `originalTag` in `Precondition.java`.

Cercato anche fuori dai file tracciati:

| Sorgente | Esito |
|---|---|
| `backend/.idea/` | Solo `RemoteRepositoriesConfiguration` di Maven (repository di artefatti, non Git) |
| `.env.example` | Solo credenziali PostgreSQL locali |
| `.env` | **Non esiste** |
| `docs/RUNNING.md`, `README.md`, tutti gli ADR e gli handoff | Nessun URL di repository |

**Il repository non nomina da nessuna parte un owner, un URL o un nome di repository remoto.**

## 3. Strumenti GitHub: disponibilità e autenticazione

| Verifica | Esito |
|---|---|
| `gh` CLI installato? | **No.** `command -v gh` → non trovato |
| Autenticazione GitHub disponibile? | **Non verificabile**: senza `gh` non c'è nulla da interrogare |
| Connettori MCP GitHub attivi in questa sessione? | Nessuno |

## 4. Configurazione CI già presente

| Percorso | Esito |
|---|---|
| `.github/` | **Non esiste** |
| `.gitlab-ci.yml` | **Non esiste** |
| `Jenkinsfile`, `.circleci/` | **Non esistono** |

Nessuna CI è mai stata configurata, in nessuna forma.

## 5. Che cosa dicono i documenti su TD-14

Attenzione: **`TD-14` è uno dei cinque identificatori che collidono** fra i due registri
(`docs/DEBT_REGISTRY.md` §2). I due significati sono diversi e solo uno è in scope qui:

| Spazio | Significato |
|---|---|
| **Vivo** (`PROJECT_STATE.md`) — *questo* | «Nessuna CI». Fuori da PHASE 2 perché una CI reale richiede un remote, che è **hard stop #4** |
| **Audit** (TASK-000) | «Build non riproducibile offline: `./mvnw -o test` fallisce per plugin assenti dalla cache». **Non rivalutato**, e non è ciò che questa task affronta |

`FINAL_HANDOFF.md` §4 lo definisce «l'unico debito il cui costo cresce **a ogni task**», e lo
mette fra le due decisioni che aspettano una persona.

## 6. Conclusione

**Tutto ciò che TD-14 richiede è disponibile tranne una cosa: la destinazione remota.**

Non c'è ambiguità da risolvere leggendo meglio il repository — **il dato non c'è**, in nessuna
forma, in nessun file, in nessuna configurazione, in nessun ref. Dedurlo dal nome della cartella
(`AI-Company-OS`) o dall'email dell'utente sarebbe esattamente l'invenzione che la regola vieta,
e un `origin` puntato a un repository sbagliato è peggio di nessun `origin`.

### Il dato esatto da fornire

**L'URL del repository remoto**, in una di queste due forme:

```
https://github.com/<owner>/<repo>.git
git@github.com:<owner>/<repo>.git
```

E, se il repository non esiste ancora, la conferma che vada **creato** — e da chi, dato che
crearlo è un'azione su un sistema esterno (**hard stop #4**) e `gh` non è installato su questa
macchina.

### Che cosa diventa possibile appena c'è

1. `git remote add origin <URL>`, `git fetch`, verifica di `push` — con autorizzazione esplicita,
   perché il push resta hard stop #4;
2. un workflow di CI che esegua `./mvnw -B clean test` con Docker disponibile (la suite richiede
   Testcontainers, quindi il runner deve avere un daemon Docker);
3. la validazione reale della CI, che senza remote **non è simulabile**: un workflow non eseguito
   non è una CI, è un file YAML.

### Perché non è stato scritto comunque un workflow

Scrivere `.github/workflows/ci.yml` presuppone **GitHub**. Il repository non dice che la
destinazione sia GitHub, e la piattaforma è parte della destinazione. Preparare un file per una
piattaforma non scelta sarebbe la stessa invenzione, solo in un'altra forma — e lascerebbe in
repository un artefatto che sembra configurato e non lo è.
