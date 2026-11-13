# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Utenti tecnici: sviluppatori e maker che costruiscono software (e progetti 3D) con agenti AI sulla propria
macchina Windows. Lavorano a lungo nella console, alternandola ai propri strumenti (Claude Code, Codex,
Antigravity, OpenCode, IDE, Blender/Unreal). Il lavoro: trasformare un'idea in un piano, assegnare le task agli
agenti giusti, approvare, rivedere, imparare da ciò che gli agenti scrivono.

## Product Purpose

AI Company OS è un ambiente operativo local-first per costruire software con l'AI tenendo una persona al centro.
Il Master Orchestrator trasforma il master prompt di un progetto in un piano di fasi e task, sceglie per ogni task
agente, modello, software e contesto, e affida il lavoro al motore AI locale o agli strumenti dell'utente; l'utente
approva piani e fasi e rivede ogni risultato. Successo: l'utente sa sempre cosa sta succedendo, cosa tocca a lui e
cosa costa.

## Positioning

Un unico posto, sul proprio PC, dove progetti, agenti, modelli, strumenti installati, skill e consumi sono letti
dalla configurazione reale della macchina e orchestrati insieme, con l'approvazione umana come passaggio obbligato.

## Operating Context

Console web servita in locale (Vite su localhost) accanto a un control plane Java e a un AI Engine Python.
Sessioni lunghe, spesso su monitor desktop; a volte in una finestra stretta accanto all'IDE o in un pannello.
Ruoli: admin e operatore.

## Capabilities and Constraints

Dashboard, progetti (master prompt, piano, fasi, documenti, Blueprint, codice, reference, strumenti), task con
orchestrazione ed esecuzione, Impara (lezioni e ripasso), agenti, Ecosistema 3D, Daily Work, Second Brain,
Software Hub, terminale, chat LLM, OpenClaw, integrazioni, Knowledge Hub, Mockup Hub, modelli, consumi,
impostazioni, esplora file. La navigazione può essere riorganizzata senza togliere funzioni.
Interfaccia in italiano. Tema scuro di default con variante chiara.

## Brand Commitments

Nome: AI Company OS. Riferimento visivo esplicito dell'utente: estetica futurista e "Apple style", minimale,
professionale, intuitiva; da evitare l'aspetto da "AI slop". Testi chiari e pronti per un prodotto commerciale,
rivolti a un pubblico tecnico (i termini tecnici restano, il gergo interno di sviluppo no: niente ADR, PHASE, TD).

## Evidence on Hand

Nessun cliente, testimonianza o metrica commerciale: non vanno inventati. I numeri in console vengono solo dai dati
reali del control plane.

## Product Principles

- L'utente vede sempre lo stato reale: niente numeri o stati inventati.
- Ogni azione che modifica o elimina dice cosa succede e se si può annullare.
- Ciò che tocca all'utente (approvare, rivedere, ripassare) viene prima di tutto il resto.
- Un solo posto per tutto, ma ogni pagina fa un lavoro solo.
