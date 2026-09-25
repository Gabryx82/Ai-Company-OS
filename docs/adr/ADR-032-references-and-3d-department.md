# ADR-032 — Reference visive dalla console e dipartimento 3D

- **Stato**: Accettata e implementata (PHASE 25)
- **Data**: 2026-09-25
- **Decisa da**: agente (roadmap V2, ex PHASE 17 ed ex PHASE 20)

## 1. Reference dalla console

`POST /api/projects/{id}/references?folder=…&name=…` con i byte dell'immagine come corpo: la console
la usa per «Aggiungi immagini» e per **incollare** (Ctrl+V) un'immagine copiata da Gemini, da uno
strumento di cattura o dal browser. Regole:

- cartella: una delle quattro del workspace (`images`, `mockups`, `screenshots`, `design-targets`);
- **i byte decidono** il formato (firma PNG, JPEG, GIF, WebP), non il nome: un file travestito è
  rifiutato;
- nome ridotto a caratteri sicuri; un file esistente non viene mai sovrascritto (suffisso numerico);
- al massimo 10 MB.

Le immagini diventano contesto per gli agenti di UI, grafica e 3D (ADR-020). Non c'è import da URL di
immagini: si scaricano dal servizio e si caricano, così nessun URL arbitrario viene aperto dal server.

## 2. Il dipartimento 3D

3D Omniverse è ora anche un **execution target** (`omniverse-3d`, consegna web): la console apre la sua
interfaccia (incorporata o in `localhost:8800`) con il prompt completo negli appunti per i suoi agenti
3D; il modello lo sceglie Omniverse. Il template **3D Artist** lavora lì di default. Omniverse parte con
AI Company OS (ADR-028) ed è incorporato in *Integrazioni* (ADR-019).

## 3. Test

`ReferencesApiTest` (immagine salvata con nome sicuro e senza sovrascrivere; file non immagine e
cartella non prevista rifiutati), `AgentBindingApiTest` (catalogo dei target).
