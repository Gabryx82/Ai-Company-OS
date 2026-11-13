---
name: AI Company OS
description: La console dell'operatore — uno strumento professionale di notte, con un solo blu e la luce usata una volta.
colors:
  bg: "#05070c"
  bg-raised: "#080b12"
  surface: "#0c1019"
  surface-2: "#121723"
  surface-3: "#1a2030"
  edge-light: "rgba(255, 255, 255, 0.045)"
  border: "rgba(148, 163, 184, 0.13)"
  border-strong: "rgba(148, 163, 184, 0.24)"
  text: "#eef1f7"
  text-soft: "#c3cad8"
  muted: "#8d96a9"
  faint: "#737c90"
  accent: "#3a8dff"
  accent-strong: "#1f6fe5"
  accent-text: "#6aa9ff"
  accent-soft: "rgba(58, 141, 255, 0.14)"
  accent-ring: "rgba(58, 141, 255, 0.55)"
  on-accent: "#ffffff"
  ok: "#3ccf8a"
  ok-soft: "rgba(60, 207, 138, 0.13)"
  warn: "#f2b84b"
  warn-soft: "rgba(242, 184, 75, 0.13)"
  danger: "#ff6b6b"
  danger-soft: "rgba(255, 107, 107, 0.13)"
  ink: "#070a11"
  ink-2: "#0d121c"
  ink-text: "#d7deeb"
  ink-muted: "#7f8aa1"
  ink-border: "rgba(148, 163, 184, 0.16)"
  icon-tile: "#171d2a"
  ribbon-core: "#d6e7ff"
  bg-light: "#f3f5f9"
  bg-raised-light: "#fbfcfe"
  surface-light: "#ffffff"
  surface-2-light: "#f2f4f8"
  surface-3-light: "#e6e9f0"
  border-light: "rgba(15, 23, 42, 0.09)"
  border-strong-light: "rgba(15, 23, 42, 0.17)"
  text-light: "#0c111d"
  text-soft-light: "#2a3243"
  muted-light: "#586174"
  faint-light: "#667085"
  accent-light: "#0a6cf0"
  accent-strong-light: "#0a62da"
  accent-text-light: "#0a5fd4"
  accent-soft-light: "rgba(10, 108, 240, 0.09)"
  ok-light: "#12804a"
  warn-light: "#9a5b00"
  danger-light: "#c93434"
  ink-light: "#0e131e"
  ink-2-light: "#151b28"
  ribbon-core-light: "#5aa8ff"
typography:
  display:
    fontFamily: "Geist Variable, Geist, -apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, system-ui, sans-serif"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.03em"
  headline:
    fontFamily: "Geist Variable, Geist, -apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, system-ui, sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.025em"
  figure:
    fontFamily: "Geist Variable, Geist, -apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, system-ui, sans-serif"
    fontSize: "26px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.03em"
    fontFeature: "\"tnum\""
  title:
    fontFamily: "Geist Variable, Geist, -apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.015em"
  body:
    fontFamily: "Geist Variable, Geist, -apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
    fontFeature: "\"cv11\", \"ss01\""
  label:
    fontFamily: "Geist Variable, Geist, -apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, system-ui, sans-serif"
    fontSize: "12.5px"
    fontWeight: 500
    lineHeight: 1.5
    letterSpacing: "normal"
  caption:
    fontFamily: "Geist Variable, Geist, -apple-system, BlinkMacSystemFont, Segoe UI Variable Text, Segoe UI, system-ui, sans-serif"
    fontSize: "11.5px"
    fontWeight: 500
    lineHeight: 1.5
    letterSpacing: "normal"
  mono:
    fontFamily: "ui-monospace, Cascadia Code, SF Mono, Menlo, Consolas, monospace"
    fontSize: "12.5px"
    fontWeight: 400
    lineHeight: 1.5
rounded:
  xs: "6px"
  sm: "8px"
  control: "9px"
  inner: "10px"
  panel-sm: "12px"
  md: "14px"
  modal: "16px"
  pill: "999px"
spacing:
  hair: "2px"
  xs: "4px"
  sm: "8px"
  md: "14px"
  lg: "18px"
  xl: "28px"
  gutter: "32px"
  gutter-phone: "16px"
components:
  button-primary:
    backgroundColor: "{colors.accent-strong}"
    textColor: "{colors.on-accent}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "6px 13px"
  button-primary-hover:
    backgroundColor: "{colors.accent}"
  button-secondary:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    padding: "6px 13px"
  button-secondary-hover:
    backgroundColor: "{colors.surface-3}"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
    padding: "6px 13px"
  button-ghost-hover:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text}"
  button-small:
    rounded: "{rounded.sm}"
    padding: "3px 10px"
  input:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    padding: "7px 11px"
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    padding: "18px"
  badge:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.muted}"
    typography: "{typography.caption}"
    rounded: "{rounded.pill}"
    padding: "1px 9px"
  badge-accent:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent-text}"
    rounded: "{rounded.pill}"
  chip:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text-soft}"
    typography: "{typography.caption}"
    rounded: "{rounded.xs}"
    padding: "1px 8px"
  nav-item:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
    padding: "6px 10px"
  nav-item-active:
    backgroundColor: "{colors.surface-2}"
    textColor: "{colors.text}"
  tab-active:
    backgroundColor: "{colors.surface-3}"
    textColor: "{colors.text}"
    rounded: "{rounded.sm}"
    padding: "5px 13px"
  icon-tile:
    backgroundColor: "{colors.icon-tile}"
    rounded: "{rounded.inner}"
    size: "40px"
  search:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.muted}"
    rounded: "{rounded.inner}"
    padding: "0 8px 0 11px"
---

# Design System: AI Company OS

## Overview

**Creative North Star: "Lo strumento di notte"**

La console è uno strumento professionale usato a lungo, di sera, accanto all'IDE: lavorata come una macchina, non decorata. Un fondo blu-nero quasi assoluto, pannelli un solo gradino più chiari, ciascuno chiuso da un bordo sottile come un capello e da un filo di luce sul lato superiore. Il colore ha un solo compito, e lo svolge un solo blu: dire che cosa è selezionato, qual è l'azione principale, che cosa è in corso. La luce vera, quella che si vede, compare una volta sola: il nastro luminoso disegnato dietro il benvenuto, nella scheda di sistema e nell'accesso.

La densità è quella di uno strumento: testo a 14px, righe di elenco separate da filetti, cifre tabellari, nessuna superficie che si sforzi di attirare l'attenzione. La console rifiuta in modo esplicito il cruscotto "neon" di default, con tessere di icone colorate e luminose e sfumature dappertutto: le tessere di icone sono neutre, le icone sono tratti singoli, i gradienti restano fuori dai pannelli. Il tema chiaro rispecchia lo stesso mondo su un bianco carta freddo, ridefinendo i token e nient'altro; codice, terminali e tele restano scuri in entrambi i temi.

**Key Characteristics:**
- Fondo blu-nero, superfici a gradini tonali, bordi a capello invece delle ombre.
- Un solo accento blu per selezione, azione primaria e avanzamento.
- La luce usata una volta: il nastro, in tre posti soltanto.
- Geist con tracking stretto e misurato; cifre tabellari ovunque si conta.
- Icone a tratto singolo da 1.8, su tessere neutre.
- Tema scuro di default, variante chiara sugli stessi token; l'"inchiostro" resta scuro in entrambi.

## Colors

Una scala di neutri blu-ardesia a gradini stretti, un unico blu e tre colori di stato tenuti al loro posto.

### Primary
- **Blu di sistema** (`accent`, scuro / `accent-light`, chiaro): il colore della selezione, dell'icona della voce corrente nella barra laterale, del bordo d'un campo a fuoco, delle barre di avanzamento e del passo corrente. Il bottone primario usa la variante più profonda (`accent-strong`) e torna al blu pieno al passaggio del mouse.
- **Blu per il testo** (`accent-text`): link, contatori e badge in accento; più chiaro del blu pieno sul fondo scuro, più scuro sul fondo chiaro, così da restare leggibile.
- **Velo blu** (`accent-soft`) e **anello blu** (`accent-ring`): lo sfondo di ciò che è premuto o scelto, l'alone di 3px attorno a un campo a fuoco, il contorno di 2px del fuoco da tastiera.

### Neutral
- **Notte profonda** (`bg`): il fondo della pagina.
- **Notte rialzata** (`bg-raised`): la barra laterale, le colonne della board, il contenitore delle schede.
- **Pannello** (`surface`): carte, cassetti, finestre, menu, la casella di ricerca.
- **Pannello sollevato** (`surface-2`) e **pannello alto** (`surface-3`): bottoni, campi, hover, la scheda selezionata; tre gradini e non di più.
- **Bordo a capello** (`border`) e **bordo deciso** (`border-strong`): il primo chiude carte e righe, il secondo disegna bottoni, campi e lo stato hover delle carte.
- **Filo di luce** (`edge-light`): l'inset di 1px sul lato superiore di carte e tessere; nel tema chiaro è trasparente.
- **Testo** (`text`), **testo morbido** (`text-soft`), **attenuato** (`muted`), **tenue** (`faint`): quattro livelli, dal titolo all'etichetta di gruppo della barra laterale e alla freccia delle catene.
- **Su accento** (`on-accent`): il bianco del testo sul blu pieno.

### Stati
- **Verde operativo** (`ok`), **ambra d'attenzione** (`warn`), **rosso di pericolo** (`danger`), ciascuno con il suo velo (`*-soft`): solo per stati reali — badge, punti di stato, avvisi, il bottone che elimina. Nel tema chiaro diventano più scuri per reggere il contrasto sul bianco.

### Inchiostro e tessera
- **Inchiostro** (`ink`, `ink-2`, `ink-text`, `ink-muted`, `ink-border`): il materiale di codice, terminale, diff, tela del Blueprint e grafi. Resta scuro anche nel tema chiaro (`ink-light`, `ink-2-light`), perché il codice e le tele si leggono su fondo scuro.
- **Tessera d'icona** (`icon-tile`): il quadrato scuro su cui siede l'icona di un programma, in entrambi i temi, perché quasi tutte le icone dei programmi sono disegnate per un fondo scuro.
- **Cuore del nastro** (`ribbon-core`): la nota più chiara al centro del nastro; nel tema chiaro diventa un blu medio (`ribbon-core-light`).

### Named Rules
**La Regola del Blu Unico.** Il blu significa "selezionato, primario, in corso" e niente altro. Nessun secondo accento, nessun blu decorativo: se un elemento è blu, l'operatore deve poterci leggere uno di questi tre significati.

**La Regola dei Token Soltanto.** Ogni colore è un token su `:root`; il tema chiaro ridefinisce i token e nient'altro. Un colore scritto a mano dentro una regola è un difetto, non una variante.

**La Regola dell'Inchiostro.** Codice, terminali, diff e tele usano l'inchiostro, mai le superfici: restano scuri in entrambi i temi.

## Typography

**Display Font:** Geist Variable (con Geist, -apple-system, Segoe UI Variable Text, system-ui)
**Body Font:** Geist Variable (stessa famiglia)
**Label/Mono Font:** ui-monospace (con Cascadia Code, SF Mono, Menlo, Consolas)

**Character:** Una sola famiglia, autoospitata, con le alternative `cv11` e `ss01` attive: precisa, tecnica, senza voce propria. Il carattere viene dal tracking negativo e misurato dei titoli e dalle cifre tabellari, non da un secondo font.

### Hierarchy
- **Display** (600, 30px, 1.2, −0.03em): solo il saluto del benvenuto nella dashboard.
- **Headline** (600, 24px, 1.2, −0.025em): il titolo di pagina; 22px nell'accesso.
- **Figure** (600, 26px, 1.2, −0.03em, cifre tabellari): le cifre della striscia di sintesi; 24px nelle KPI e nelle statistiche.
- **Title** (600, 15px, 1.2, −0.015em): titoli di sezione e del marchio; 14.5px nelle intestazioni di carta, 14px per h3.
- **Body** (400, 14px, 1.5): il testo corrente; le righe di introduzione si fermano a 72ch. 15px nel benvenuto e nelle lezioni.
- **Label** (500, 12.5px): etichette dei campi, titoli di sezione attenuati, bottoni piccoli.
- **Caption** (500, 11.5px): gruppi della barra laterale, badge, chip, didascalie.
- **Mono** (12.5px): percorsi, indirizzi, codice; 11px nella scheda di sistema.

### Named Rules
**La Regola del Tracking Misurato.** Più grande è il testo, più stretto è il tracking: da −0.015em sui titoli piccoli a −0.03em sul display. Il corpo resta a spaziatura normale.

**La Regola delle Cifre Tabellari.** Ogni numero che si confronta — tabelle, cifre, percentuali, conti alla rovescia — usa cifre tabellari.

## Layout

Una griglia a due colonne: barra laterale fissa di 244px e contenuto. La barra laterale resta ferma mentre la pagina scorre; la barra superiore è sticky, traslucida sul fondo (82%) con sfocatura. Il contenuto ha padding di 28px sopra, 32px ai lati e 48px sotto, fino a 1480px di larghezza.

Il ritmo è stretto e regolare: 14px tra carte e righe della griglia, 18px tra i blocchi della dashboard e dentro le carte, 22px sotto l'intestazione di pagina. Le griglie si riempiono da sole (`auto-fit` con minimi di 150, 190 e 340px); la dashboard affianca una colonna di lavoro (1.55fr) e una colonna laterale (min 300px). Un pannello di dettaglio accanto a un elenco resta in vista (sticky, alto quanto la finestra) e scorre da solo.

Sotto i 1100px dashboard e viste divise passano a una colonna e il pannello di dettaglio torna parte della pagina. Sotto i 1080px sparisce la data dalla barra superiore. Sotto i 900px la barra laterale diventa un foglio laterale (largo fino a 300px o 86vw) aperto dal bottone menu nella barra superiore, con velo dietro, fuoco trattenuto al suo interno e chiusura con Esc o a ogni navigazione; i margini laterali scendono a 16px e la scorciatoia della ricerca si nasconde. Le tabelle larghe scorrono dentro la propria carta, mai la pagina.

## Elevation & Depth

Il sistema è piatto per tonalità: la profondità viene dai gradini di superficie (`bg` → `bg-raised` → `surface` → `surface-2` → `surface-3`), dai bordi a capello e dal filo di luce sul lato superiore delle carte. Le carte a riposo non hanno ombra. Le ombre sono riservate a ciò che galleggia sopra la pagina: cassetti, finestre modali, menu, risultati della ricerca, il foglio della barra laterale su telefono, la carta dell'accesso.

### Shadow Vocabulary
- **Filo di luce** (`box-shadow: inset 0 1px 0 var(--edge-light)`): il bordo superiore illuminato di carte e tessere.
- **Ombra** (`--shadow`: `0 18px 40px -18px rgba(0,0,0,0.7)`; chiaro `0 12px 32px -16px rgba(15,23,42,0.18)`): pannelli secondari sollevati, come l'Inspector e i menu contestuali.
- **Ombra fluttuante** (`--shadow-float`: `0 24px 60px -12px rgba(0,0,0,0.75), 0 0 0 1px rgba(148,163,184,0.14)`; chiaro `0 24px 60px -14px rgba(15,23,42,0.28), 0 0 0 1px rgba(15,23,42,0.08)`): ciò che sta sopra tutto — cassetto, modale, menu dell'account, risultati della ricerca, foglio di navigazione, carta dell'accesso. Porta con sé il proprio anello di 1px, quindi non serve un bordo.
- **Velo** (`--overlay` con sfocatura di 3px): lo sfondo dietro cassetti, modali e foglio di navigazione.

### Named Rules
**La Regola del Capello.** Le carte si separano con un bordo di 1px e un gradino di tonalità, mai con un'ombra ampia. Un'ombra dice "questo galleggia", non "questo è importante".

**La Regola della Luce Unica.** La luce visibile — il nastro — compare solo dietro il benvenuto della dashboard, nella scheda di sistema ai piedi della barra laterale e nella pagina d'accesso. Da nessun'altra parte: niente bagliori, alonature colorate o sfondi luminosi altrove.

## Shapes

Angoli morbidi e coerenti, scalati con la dimensione dell'oggetto: 14px per pannelli, carte e tessere; 16px per le finestre modali; 12px per menu, risultati della ricerca, la scheda di sistema e le carte interne; 10px per avvisi, blocchi di codice, la ricerca e le tessere d'icona; 9px per bottoni, campi, voci di navigazione e bottoni-icona; 8px per bottoni piccoli e voci di menu; 6px per chip e tasti. Le pillole (999px) sono solo per controlli piccoli: badge, contatori, la barra di avanzamento, il chip dell'utente. L'avatar è un cerchio.

I bordi sono sempre di 1px; il tratteggio è riservato al riquadro che descrive ciò che si sta per assegnare. Le icone sono tracciate su una griglia di 24, a tratto singolo di 1.8 con estremità e giunzioni arrotondate.

## Components

### Buttons
Compatti e precisi, mai gonfi.
- **Shape:** angoli da strumento (9px); bottone piccolo a 8px.
- **Primary:** blu profondo con testo bianco e un filo di luce interno, padding 6px 13px, peso 500 a 13px. Uno per vista, per l'azione che porta avanti il lavoro.
- **Hover / Focus:** al passaggio il primario torna al blu pieno, il secondario sale di un gradino; alla pressione un affondo di mezzo pixel. Il fuoco da tastiera è un contorno di 2px con l'anello blu, staccato di 2px.
- **Secondary:** pannello sollevato con bordo deciso. **Ghost:** trasparente e attenuato, per le azioni di intestazione ("Apri la board", "Vedi tutti"). **Danger:** testo rosso, velo rosso al passaggio.
- **Disabled:** opacità 0.45, cursore non consentito.

### Chips
- **Badge:** pillole a 11.5px, neutre con anello a capello; le varianti di stato (ok, attenzione, pericolo, accento) usano il proprio velo e il proprio colore, senza anello.
- **Chip:** etichette rettangolari da 6px, testo morbido su pannello sollevato, per tipi e appartenenze (un progetto, un tipo di risultato).

### Cards / Containers
- **Corner Style:** 14px.
- **Background:** pannello (`surface`).
- **Shadow Strategy:** nessuna ombra a riposo; solo il filo di luce superiore (vedi Elevation & Depth).
- **Border:** 1px a capello; al passaggio, sulle carte cliccabili, il bordo deciso e un sollevamento di 1–2px.
- **Internal Padding:** 18px; intestazione di carta 14px 18px con filetto sotto; righe di elenco 11px 18px separate da filetti.

### Inputs / Fields
- **Style:** pannello sollevato, bordo deciso, angoli di 9px, padding 7px 11px; segnaposto in tono tenue.
- **Focus:** bordo blu e alone di 3px nel velo blu, nessun contorno di sistema.
- **Error / Disabled:** messaggio d'errore in rosso a 12px sotto il campo; suggerimento attenuato a 12px.

### Navigation
- **Barra laterale:** su notte rialzata, con il marchio in alto, voci senza gruppo (Dashboard, Agenda), poi quattro gruppi brevi (Lavoro, Squadra, Strumenti, Conoscenza) con etichetta tenue a 11.5px in maiuscolo di frase; Consumi e Impostazioni ai piedi, insieme alla scheda di sistema. Voci a 13.5px peso 500, attenuate; al passaggio pannello sollevato e testo pieno; la voce corrente ha il pannello sollevato, un anello a capello e la sola icona in blu.
- **Barra superiore:** traslucida e sticky; bottone dei file, ricerca globale (Ctrl K), data, interruttore del tema (sole/luna), chip dell'utente con avatar e menu.
- **Schede:** una riga segmentata su notte rialzata; la scheda corrente sale al pannello alto con il filo di luce.
- **Marchio:** un quadrato di 30px (40px nell'accesso) con angoli di 9px, l'unico riempimento a gradiente della console (blu dal chiaro al profondo, 160°) con la scintilla bianca disegnata; l'avatar ne ripete il blu in un cerchio.
- **Telefono:** sotto i 900px la barra laterale è un foglio scorrevole da sinistra (0.28s, curva di uscita), aperto dal bottone menu.

### Ricerca globale
Una casella di pannello con bordo deciso, icona di lente, segnaposto "Cerca progetti, task, agenti e software" e tasto `Ctrl K` disegnato come un tasto a capello. A fuoco prende il bordo blu e l'alone; i risultati galleggiano sotto con l'ombra fluttuante, ciascuno con icona, nome e chip del tipo.

### Il nastro (componente firma)
Sedici linee sottili, tracciate come geometria SVG, che attraversano il campo in diagonale con un gradiente dal blu al cuore chiaro e di nuovo al blu, più una scia sfocata sotto (`--ribbon-haze`). Dove una vista lo porta all'apertura, le linee si disegnano una volta (1.6s, sfasate di 35ms), e restano ferme con il movimento ridotto. Vive solo dietro il benvenuto della dashboard, nella scheda di sistema e nell'accesso.

### Tessera d'icona
L'icona di un programma siede su un quadrato scuro (`icon-tile`) con angoli di 10px e un bordo bianco all'8%, in entrambi i temi; senza icona, le iniziali del nome in una tinta per categoria. Le tessere decorative delle KPI sono neutre: pannello sollevato, anello a capello, icona in testo morbido.

### Catena di assegnazione
Agente → Modello → Provider → Destinazione: colonne dentro una sola carta, non scatole annidate, separate dall'icona freccia disegnata (tratto singolo, 14px, tono tenue).

### Striscia di sintesi
Una carta con cinque cifre, separate da filetti disegnati su ogni cella (lato sinistro e superiore, ritagliati dalla carta); ogni cifra è un bottone che apre la sua pagina, con etichetta attenuata e icona da 15px.

## Do's and Don'ts

### Do:
- **Do** usa il blu (`accent`) solo per selezione, azione primaria e avanzamento; un solo bottone primario per vista.
- **Do** separa le carte con un bordo di 1px (`border`) e un gradino di superficie; riserva `--shadow-float` a ciò che galleggia.
- **Do** dichiara ogni nuovo colore come token su `:root` e ridefiniscilo in `:root[data-theme="light"]`.
- **Do** metti codice, terminali, diff e tele sull'inchiostro (`ink`), che resta scuro in entrambi i temi.
- **Do** posa le icone dei programmi sulla tessera scura (`icon-tile`) e tieni neutre le tessere d'icona decorative.
- **Do** usa le icone a tratto singolo del set della console, compresa la freccia disegnata nelle catene.
- **Do** dai a ogni elemento interattivo il contorno di fuoco di 2px con `accent-ring`.
- **Do** rispetta il movimento ridotto: le animazioni d'ingresso e il disegno del nastro si spengono.

### Don't:
- **Don't** aggiungere un secondo accento o tessere d'icona colorate e luminose: è il cruscotto "neon" che la console rifiuta.
- **Don't** usare il nastro, bagliori o sfondi luminosi fuori dal benvenuto della dashboard, dalla scheda di sistema e dall'accesso.
- **Don't** dare alle carte ombre ampie a riposo né riempire pannelli con gradienti.
- **Don't** scrivere colori esadecimali dentro le regole dei componenti al posto dei token.
- **Don't** usare caratteri tipografici come icone (frecce, triangoli) al posto delle icone disegnate.
- **Don't** usare titoletti in maiuscolo spaziato sopra i titoli o le sezioni.
- **Don't** mettere codice o terminali su una superficie che diventa chiara con il tema chiaro.
