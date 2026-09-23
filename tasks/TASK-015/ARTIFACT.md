# TASK-015 — Artefatto

TD-37 **chiuso**. **TD-38 aperto** (storia delle transizioni). Suite **244 → 260**. Nessuna migrazione:
il vincolo `tasks_status_check` di `V7` copre già i tre valori.

## Verifica per mutazione (albero pulito prima e dopo ciascuna)
| # | Mutazione | Esito |
|---|---|---|
| M1 | niente congelamento | rosso (2) |
| M2 | niente controllo dell'arco | rosso (2) |
| M3 | niente controllo «senza agente» | rosso (1) |
| M4 | niente controllo «agente attivo» | rosso (1) |
| M5 | niente precondizione | rosso (2) |
| M6 | la regola sull'agente su ogni arco | rosso (2) — protegge D3 |
| M7 | niente lock di riga (`findById`) | **verde, poi rosso.** Il primo test di concorrenza sparava due chiamate HTTP a una barriera e restava verde senza lock: le chiamate non si sovrapponevano. Sostituito da `TaskLifecycleConcurrencyTest`, che tiene il primo chiamante dentro la transazione dopo il lock. Rieseguita: **rosso** |

## Rilievi
- **MEDIUM (corretto)**: il test a barriera prometteva «whatever the interleaving» senza controllarlo.
- **LOW**: `requireAgentForDecision` su `start` non è strettamente necessario (ADR-014 §5); tenuto per L7.
