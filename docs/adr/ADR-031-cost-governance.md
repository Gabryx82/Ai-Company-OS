# ADR-031 — Governo dei costi

- **Stato**: Accettata e implementata (PHASE 24)
- **Data**: 2026-09-25
- **Decisa da**: agente (roadmap V2, ex PHASE 19; chiude TD-40)

## 1. Decisione

Tre regole, applicate dal control plane prima che qualunque richiesta parta:

1. **ogni run conclusa registra il suo costo** (`task_runs.cost_usd`, V24), al prezzo del momento:
   zero per i modelli locali e per gli abbonamenti, sconosciuto (nullo) se un modello a consumo non ha
   prezzo;
2. una run su un provider **a consumo** (`PAY_PER_TOKEN`: Anthropic API, OpenRouter) richiede un
   **budget mensile** per quel provider (`409 budget-required`) e un **prezzo** per quel modello
   (`409 price-required`);
3. quando la spesa del mese raggiunge il budget, le run di quel provider si fermano
   (`409 budget-exceeded`) fino al mese successivo o a un budget più alto.

Le run locali (Ollama) e gli handoff agli strumenti ad abbonamento (Claude Code, Codex…) non sono
toccate: non costano per esecuzione.

## 2. Chi decide

Prezzi (USD per milione di token, `llm_models`) e budget (`cost_budgets`: limite mensile e soglia di
avviso) sono decisioni dell'**admin**, sotto `/api/admin/costs/**`. Un budget si crea senza tag e si
modifica con il suo (ADR-009); un prezzo si modifica col tag del modello. **Nessun prezzo è stato
inventato**: i listini cambiano, e un prezzo sbagliato darebbe una contabilità falsa; finché non è
impostato, il modello a consumo non gira.

## 3. Che cosa si vede

`GET /api/costs` (mese corrente di default): spesa per provider, per modello, per agente; budget con
speso, percentuale, soglia, esaurito. La console lo mostra in **Consumi → Costi delle esecuzioni**; i
prezzi si impostano in **Modelli**.

## 4. Test

`CostGovernanceApiTest`: senza budget rifiutata, senza prezzo rifiutata, con entrambi eseguita e costo
registrato esatto (7 + 7 token a 15/75 USD per milione = 0,00063), riepilogo, budget abbassato con tag
→ esaurito → run a consumo rifiutata e run locale eseguita a costo zero; operatore senza accesso.
