---
version: 1
slug: "frontend-src-app-tsx"
primary_target: "frontend/src/App.tsx"
related_targets: ["frontend/src/styles.css"]
---

Scope: the whole operator console (shell, every page, the login). Mode: Operate. Audience: technical makers who
run AI agents on their own machine for long sessions. Job: know what is happening, what is theirs to do, open it.
Constraints: Italian copy, every function kept, navigation may be regrouped, dark default plus a light variant,
copy ready for a commercial product (no internal ADR/PHASE/TD jargon). Direction pinned by the user's reference
image (futuristic, Apple-like, minimal), so no concept roll.

## Direction contract

THESIS: a pro tool at night, machined rather than decorated; it refuses the neon-dashboard default of glowing
multicolour icon tiles and gradient everything. Calm surfaces, one blue, light used once.

OWN-WORLD: near-black navy ground; panels one step lighter with a hairline border and a faint top edge of light;
one accent, a clear system blue, used for selection, primary action and progress; neutral icons in a single
stroke; Geist for text and headings with tight, measured tracking; 14px corners on panels, pills only on small
controls; the light theme mirrors it on cool paper white.

STORY: the user lands, sees whose turn it is (what to approve, review, continue), reads the state of projects,
tasks and agents at a glance, and reaches any area in two clicks from a short grouped sidebar or the search.

FIRST VIEWPORT: sidebar with brand, four short groups and the account at the foot; top bar with search
(Ctrl K) and theme toggle; dashboard hero with greeting, one line of status and the primary action, the
signature light ribbon drawn behind it; below, one hairline strip of five figures (the five the console can count truthfully: projects, tasks in progress, enabled agents, ready software, available models; each opens its page), then "Adesso" and the working lists in two columns.

FORM: pinned by the user's reference image (no seed key; concept-seed not run because the direction is pinned).
Signature move: a single luminous blue ribbon (SVG geometry) in the dashboard hero and the sidebar foot card,
nowhere else.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict,
DESIGN.md, and every shipping raster carrying its provenance
