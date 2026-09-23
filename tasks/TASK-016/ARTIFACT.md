# TASK-016 — Artefatto

TD-36 **chiuso**. Suite **260 → 277**. Stream **`V8` → `V9`**.

## Verifica per mutazione (albero pulito prima e dopo ciascuna)
| # | Mutazione | Esito |
|---|---|---|
| M1 | niente congelamento su `updateDetails` | rosso |
| M2 | niente vincolo di vocabolario sulla creazione | rosso (2): `valueOf` → `500` |
| M3 | `V9` senza `MEDIUM` | rosso (3) |
| M4 | filtro ignorato | rosso |
| M5 | niente precondizione su `update` | rosso |
| M6 | `V9` svuotata | rosso (2, `MigrationStreamTest`) |

## Upgrade
`MigrationStreamTest`: `V8 → V9` su database popolato con **entrambi** i valori del censimento
(con uno solo, un vincolo che accettasse solo quello passerebbe); `V9` si ferma su `'whenever'` e
la riga resta intatta. `everyConsecutiveUpgradePreservesWhatWasAlreadyThere` copre `V8 → V9` da sé.

## Rotture dichiarate
`priority` fuori vocabolario passa da `201` a `400`. JSON invariato.
