# ADR-028 — Avvio coordinato dell'ecosistema

- **Stato**: Accettata e implementata (PHASE 21)
- **Data**: 2026-09-25
- **Decisa da**: agente, su direttiva umana del 2026-09-25 (§7 «Avvio coordinato dell'ecosistema»)
- **Estende**: ADR-019 (Software Hub e launcher)

## 1. Decisione

Quando il control plane è pronto (`ApplicationReadyEvent`), in un thread proprio, avvia i servizi
dell'ecosistema marcati «autoavvio», nell'ordine configurato. Default su un database nuovo:

| Ordine | Servizio | Attesa massima |
|---|---|---|
| 1 | Ollama | 60 s |
| 2 | Open WebUI | 120 s |
| 3 | 3D Omniverse | 120 s |

Per ciascuno:

1. **già attivo?** (risponde il suo URL di salute) → `ALREADY_RUNNING`, **nessun nuovo avvio**;
2. altrimenti lo avvia **come il Software Hub** (ADR-019: eseguibile e argomenti del catalogo, con le
   variabili d'ambiente, senza shell) → `STARTING`, evento `PROCESS_STARTED` nel registro;
3. in background interroga il suo URL di salute finché risponde (`RUNNING`) o finché scade l'attesa
   (`FAILED`, con il motivo).

Un servizio che fallisce resta `FAILED` con il messaggio, e **il successivo parte comunque**: uno
strumento secondario non ferma mai AI Company OS, e il control plane risponde subito, senza aspettare.

## 2. Configurabile, senza percorsi della macchina nel codice

- **Come** si avvia: nel catalogo del Software Hub (eseguibile, argomenti, URL di salute), con
  variabili d'ambiente (`%USERPROFILE%\3D Omniverse\start.ps1`, `%LOCALAPPDATA%\Programs\…`). Si
  modifica da *Impostazioni → Ecosistema all'avvio → Configura*.
- **Se, in che ordine, quanto attendere**: tabella `ecosystem_autostart` (V23), stessa finestra.
- Entrambe le cose sono **decisioni dell'admin**: definiscono quali programmi il sistema esegue. Da
  PHASE 21 anche `POST /api/software` e `PUT /api/software/{key}` sono solo per admin.
- `aicos.ecosystem.autostart=false` spegne l'avvio automatico (i test lo fanno sempre).

## 3. Stato visibile

`GET /api/ecosystem/services`: disponibilità attuale, ultimo esito, messaggio, ora. La console lo
mostra in *Integrazioni* e in *Impostazioni*, con «Avvia» (disattivato per ciò che è già attivo) e
«Avvia quelli automatici».

## 4. Test

`EcosystemApiTest` (default e ordine, niente doppio avvio, avvio e passaggio a `RUNNING`, un errore non
ferma gli altri, `FAILED` dopo l'attesa, configurazione solo admin con If-Match),
`SoftwareHubApiTest` (un operatore non cambia cosa esegue il launcher); console: `EcosystemPanel.test`.
