# TASK-021 — Implementazione

Normalizzazione del file committato, perché cambi solo quando cambia il contratto: chiavi ordinate
ricorsivamente, `servers` rimosso (contiene l'URL della richiesta), `LF` ovunque (il pretty printer
usa il separatore di piattaforma, e il file deve essere identico su Windows e sul runner Linux).
Il primo tentativo falliva proprio per `\r\n`: trovato al primo giro, corretto prima del commit.
