import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useApi } from "../../context";
import { ApiProblem } from "../../api/client";
import type {
  AiAction, BlueprintAiAction, BlueprintBlockDetail, BlueprintBlockWrite, BlueprintConnection, BlueprintGraph, BlueprintRelation, BlueprintSnippet, BlueprintVocabulary,
  CodeStructure, NodeKind, NodeStatus, PathState, RelationType,
} from "../../api/types";
import { ProblemNote, when } from "../../components/ui";
import { StructurePanel } from "./StructurePanel";
import { KIND_LOOK, ORIGIN_LABEL, PATH_STATE_LABEL, RELATION_COLOR, STATUS_LOOK } from "./blueprintStyle";

export interface InspectorActions {
  focus: (nodes: number[], edges?: number[]) => void;
  centerNeighbours: (nodeId: number, direction: "out" | "in") => void;
  addRelationFrom: (nodeId: number) => void;
  openFile: (nodeId: number) => void;
  openTask: (taskId: number) => void;
  changed: () => void;
  proposalsChanged: () => void;
}

interface Draft {
  kind: NodeKind; name: string; filePath: string; language: string; description: string; pseudocode: string; notes: string;
  tags: string[]; status: NodeStatus; pathState: PathState; structure: CodeStructure;
}

function draftOf(d: BlueprintBlockDetail): Draft {
  return { kind: d.kind, name: d.name, filePath: d.filePath ?? "", language: d.language ?? "", description: d.description ?? "",
    pseudocode: d.pseudocode ?? "", notes: d.notes ?? "", tags: d.tags, status: d.status, pathState: d.pathState,
    structure: d.structure };
}

/**
 * The Inspector of a block (directive §6, §10): what it is, what it does, what
 * it is made of, how it works, what it touches, its code, and what can be done
 * with it. Every field a scan wrote says so -- "Rilevato dal codice" -- or says
 * that a person changed it, with the way back.
 */
export function BlockInspector({ projectId, nodeId, vocabulary, editable, actions, onClose }: {
  projectId: number; nodeId: number; vocabulary: BlueprintVocabulary; editable: boolean; actions: InspectorActions;
  onClose: () => void;
}) {
  const api = useApi();
  const [detail, setDetail] = useState<BlueprintBlockDetail | null>(null);
  const [etag, setEtag] = useState("");
  const [draft, setDraft] = useState<Draft | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [snippet, setSnippet] = useState<BlueprintSnippet | null>(null);
  const [action, setAction] = useState<AiAction>("EXPLAIN_COMPONENT");
  const [outcomes, setOutcomes] = useState<BlueprintAiAction[]>([]);

  const load = useCallback(() => {
    setProblem(null);
    api.blueprintNode(projectId, nodeId).then(({ body, etag }) => {
      setDetail(body); setEtag(etag); setDraft(draftOf(body));
    }).catch(setProblem);
    // PHASE 32 (TD-63): what the AI actions asked about this block produced
    api.blueprintAiActions(projectId, nodeId).then(setOutcomes).catch(() => setOutcomes([]));
  }, [api, projectId, nodeId]);

  useEffect(() => { setSnippet(null); setNote(null); load(); }, [load]);

  async function run(work: () => Promise<{ body: BlueprintBlockDetail; etag: string } | void>, done?: string) {
    setBusy(true); setProblem(null); setNote(null);
    try {
      const result = await work();
      if (result) { setDetail(result.body); setEtag(result.etag); setDraft(draftOf(result.body)); }
      if (done) setNote(done);
      actions.changed();
    } catch (error) {
      setProblem(error);
      // 412 is never retried (ADR-017): read again, and let the person decide again
      if (error instanceof ApiProblem && error.isStale) load();
    } finally {
      setBusy(false);
    }
  }

  if (!detail || !draft) {
    return <aside className="bp-inspector"><ProblemNote problem={problem} /><div className="muted">Carico il blocco…</div></aside>;
  }
  const d = detail;
  const detected = d.origin === "DETECTED";
  const dirty = JSON.stringify(draft) !== JSON.stringify(draftOf(d));
  const look = KIND_LOOK[d.kind];
  const kindLabel = (k: NodeKind) => vocabulary.kinds.find((x) => x.kind === k)?.label ?? k;
  const relationLabel = (r: RelationType) => vocabulary.relations.find((x) => x.relation === r)?.label ?? r;
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft({ ...draft, [key]: value });
  // null and absent are the same to the server; the generated request type only knows absent
  const write = (): BlueprintBlockWrite => ({ ...draft, filePath: draft.filePath || undefined, language: draft.language || undefined,
    description: draft.description || undefined, pseudocode: draft.pseudocode || undefined, notes: draft.notes || undefined,
    structure: draft.structure as unknown as BlueprintBlockWrite["structure"] });

  const provenance = (field: string) => !detected ? null : d.overrides.includes(field)
    ? <span className="bp-prov is-manual">Modifica manuale
        {editable && <button type="button" className="bp-link" disabled={busy}
          onClick={() => run(() => api.revertBlueprintNode(projectId, d.id, etag, [field]), "Ripristinato il valore del codice.")}>ripristina</button>}
      </span>
    : <span className="bp-prov">Rilevato dal codice</span>;

  return (
    <aside className="bp-inspector" aria-label="Inspector del blocco">
      <div className="bp-insp-head" style={{ ["--kind" as string]: look.color }}>
        <span className="bp-mono" aria-hidden>{look.mono}</span>
        <div style={{ minWidth: 0 }}>
          <h2 title={d.name}>{d.name}</h2>
          <div className="muted" style={{ fontSize: 12 }}>{kindLabel(d.kind)} · {detected ? "rilevato dal codice" : "manuale"}</div>
        </div>
        <span className="spacer" />
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi l'Inspector">×</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {note && <div className="notice">{note}</div>}

      <Section title="Overview">
        <div className="bp-kv">
          <dt>Nome {provenance("name")}</dt>
          <dd>{editable ? <input aria-label="Nome" value={draft.name} onChange={(e) => set("name", e.target.value)} /> : d.name}</dd>
          <dt>Tipo {provenance("kind")}</dt>
          <dd>{editable
            ? <select aria-label="Tipo" value={draft.kind} onChange={(e) => set("kind", e.target.value as NodeKind)}>
                {vocabulary.kinds.map((k) => <option key={k.kind} value={k.kind}>{k.label}</option>)}
              </select>
            : kindLabel(d.kind)}</dd>
          <dt>Path {detected && <span className="bp-prov">Rilevato dal codice</span>}</dt>
          <dd>
            {editable && !detected
              ? <input aria-label="Percorso" className="mono" value={draft.filePath ?? ""} placeholder="percorso/del/file.ext"
                       onChange={(e) => set("filePath", e.target.value)} />
              : d.filePath ? <span className="mono bp-break">{d.filePath}{d.startLine ? `:${d.startLine}` : ""}</span> : <span className="muted">—</span>}
            {d.filePath && <button type="button" className="bp-link" onClick={() => navigator.clipboard?.writeText(d.filePath ?? "")}>copia</button>}
            <div className="muted" style={{ fontSize: 11.5 }}>{PATH_STATE_LABEL[d.pathState]}</div>
          </dd>
          {editable && !detected && draft.filePath && d.pathState !== "LINKED" && (
            <><dt>Se il file non esiste</dt>
              <dd><select aria-label="Stato del percorso" value={draft.pathState ?? "DOCUMENT_REFERENCE"}
                          onChange={(e) => set("pathState", e.target.value as PathState)}>
                {(["TO_CREATE", "DOCUMENT_REFERENCE", "VIRTUAL"] as PathState[]).map((p) => <option key={p} value={p}>{PATH_STATE_LABEL[p]}</option>)}
              </select></dd></>
          )}
          <dt>Linguaggio</dt>
          <dd>{editable && !detected ? <input aria-label="Linguaggio" value={draft.language ?? ""} onChange={(e) => set("language", e.target.value)} />
            : d.language ?? <span className="muted">—</span>}</dd>
          <dt>Stato</dt>
          <dd>{editable
            ? <select aria-label="Stato" value={draft.status ?? "PLANNED"} onChange={(e) => set("status", e.target.value as NodeStatus)}>
                {vocabulary.statuses.map((s) => <option key={s} value={s}>{STATUS_LOOK[s].label}</option>)}
              </select>
            : <><span className={`dot ${STATUS_LOOK[d.status].dot}`} /> {STATUS_LOOK[d.status].label}</>}</dd>
        </div>
      </Section>

      <Section title="Description" extra={provenance("description")}>
        {editable
          ? <textarea aria-label="Descrizione" rows={3} value={draft.description ?? ""} onChange={(e) => set("description", e.target.value)} />
          : <p className="bp-prose">{d.description ?? <span className="muted">Nessuna descrizione.</span>}</p>}
      </Section>

      <Section title="Code Structure" extra={detected ? <span className="bp-prov">Rilevato dal codice</span> : null}>
        <StructurePanel structure={editable && !detected ? draft.structure : d.structure} section={d.section}
                        editable={editable && !detected} onChange={(s) => set("structure", s)} />
        {d.expandable && editable && (
          <button type="button" className="btn btn-small" disabled={busy} style={{ marginTop: 8 }}
                  onClick={() => run(() => api.setBlueprintNodeExpanded(projectId, d.id, etag, !d.expanded),
                    d.expanded ? "Metodi compressi." : "Ogni metodo è ora un blocco.")}>
            {d.expanded ? "Comprimi i metodi" : "Espandi i metodi in blocchi"}
          </button>
        )}
      </Section>

      <Section title="Pseudocode" extra={provenance("pseudocode")}>
        {editable
          ? <textarea aria-label="Pseudocodice" className="mono bp-code-input" rows={8} value={draft.pseudocode ?? ""}
                      onChange={(e) => set("pseudocode", e.target.value)} />
          : d.pseudocode ? <pre className="bp-pseudo bp-pseudo-large">{d.pseudocode}</pre> : <span className="muted">Nessuno pseudocodice.</span>}
        {editable && (
          <button type="button" className="btn btn-small" disabled={busy || dirty} style={{ marginTop: 6 }}
                  title={dirty ? "Salva prima le modifiche" : undefined}
                  onClick={() => run(() => api.regenerateBlueprintPseudocode(projectId, d.id, etag), "Pseudocodice rigenerato.")}>
            {detected ? "Rigenera dal codice" : "Genera / aggiorna"}
          </button>
        )}
      </Section>

      {editable && (
        <div className="bp-savebar">
          <button className="btn btn-primary" disabled={!dirty || busy || !draft.name?.trim()}
                  onClick={() => run(() => api.updateBlueprintNode(projectId, d.id, etag, write()), "Salvato.")}>
            {busy ? "Salvo…" : "Salva modifiche"}
          </button>
          {dirty && <button className="btn" disabled={busy} onClick={() => setDraft(draftOf(d))}>Annulla</button>}
          <span className="muted" style={{ fontSize: 11.5 }}>Nessun file sorgente viene modificato.</span>
        </div>
      )}

      <Section title={`Connections (${d.incoming.length + d.outgoing.length})`}>
        <Connections title="Incoming" items={d.incoming} arrow="←" relationLabel={relationLabel}
                     onFocus={(id, edge) => actions.focus([id, d.id], [edge])} />
        <Connections title="Outgoing" items={d.outgoing} arrow="→" relationLabel={relationLabel}
                     onFocus={(id, edge) => actions.focus([id, d.id], [edge])} />
        {editable && <button type="button" className="btn btn-small" onClick={() => actions.addRelationFrom(d.id)}>+ Relazione</button>}
      </Section>

      {d.filePath && d.pathState !== "TO_CREATE" && d.pathState !== "VIRTUAL" && (
        <Section title="Code Snippet" open={false}>
          {snippet
            ? <CodeLines snippet={snippet} />
            : <button type="button" className="btn btn-small" onClick={() => api.blueprintSnippet(projectId, d.id).then(setSnippet).catch(setProblem)}>
                Mostra l'estratto
              </button>}
        </Section>
      )}

      <Section title="Metadata" open={false}>
        <div className="bp-kv">
          <dt>Tags</dt>
          <dd>{editable
            ? <input aria-label="Tag" value={draft.tags?.join(", ") ?? ""} placeholder="api, core"
                     onChange={(e) => set("tags", e.target.value.split(",").map((t) => t.trim()).filter(Boolean))} />
            : d.tags.length ? d.tags.map((t) => <span key={t} className="chip">#{t}</span>) : <span className="muted">—</span>}</dd>
          <dt>Note</dt>
          <dd>{editable ? <textarea aria-label="Note" rows={2} value={draft.notes ?? ""} onChange={(e) => set("notes", e.target.value)} />
            : d.notes ?? <span className="muted">—</span>}</dd>
          <dt>Origine</dt><dd>{detected ? "Rilevato dal codice (scansione)" : "Manuale"}</dd>
          <dt>Ultima scansione</dt><dd>{detected ? when(d.lastScanAt) : "—"}</dd>
          <dt>Aggiornato</dt><dd>{when(d.updatedAt)}</dd>
          <dt>Chiave</dt><dd className="mono bp-break" style={{ fontSize: 11 }}>{d.key}</dd>
        </div>
      </Section>

      {outcomes.length > 0 && (
        <Section title="Esiti delle azioni AI">
          <ul className="history" aria-label="Esiti delle azioni AI">
            {outcomes.map((o) => (
              <li key={o.id}>
                <div className="row">
                  <strong>{vocabulary.actions.find((a) => a.action === o.action)?.label ?? o.action}</strong>
                  <button type="button" className="bp-link" onClick={() => actions.openTask(o.taskId)}>task #{o.taskId}</button>
                  <span className="muted">{o.taskStatus} · {when(o.requestedAt)}</span>
                </div>
                {o.outcome
                  ? <>
                      <pre className="bp-pseudo" style={{ whiteSpace: "pre-wrap" }}>{o.outcome}</pre>
                      <span className="muted" style={{ fontSize: 12 }}>Da {o.outcomeSource}{o.outcomeAt ? ` · ${when(o.outcomeAt)}` : ""}</span>
                      {editable && o.appliesTo && (
                        <button type="button" className="btn btn-small" style={{ marginLeft: 8 }} disabled={busy}
                                onClick={() => run(() => api.updateBlueprintNode(projectId, d.id, etag,
                                  { ...write(), [o.appliesTo as string]: o.outcome ?? undefined }),
                                  "Applicato al blocco: è una modifica manuale, ripristinabile.")}>
                          {o.appliesTo === "pseudocode" ? "Usa come pseudocodice" : "Usa come descrizione"}
                        </button>
                      )}
                    </>
                  : <span className="muted" style={{ fontSize: 12 }}>Nessun esito ancora: arriva con un'esecuzione riuscita o con la sezione Esito del documento della task.</span>}
              </li>
            ))}
          </ul>
        </Section>
      )}

      <Section title="Actions">
        <div className="bp-actions">
          {d.filePath && d.pathState === "LINKED" && <button type="button" className="btn btn-small" onClick={() => actions.openFile(d.id)}>Apri file</button>}
          <button type="button" className="btn btn-small" onClick={() => actions.centerNeighbours(d.id, "out")}>Centra dipendenze</button>
          <button type="button" className="btn btn-small" onClick={() => actions.centerNeighbours(d.id, "in")}>Centra dipendenti</button>
          {editable && d.pathState === "TO_CREATE" && (
            <button type="button" className="btn btn-small" disabled={busy}
                    onClick={() => run(async () => {
                      await api.proposeBlueprintChange(projectId, { kind: "CREATE_FILE", nodeId: d.id });
                      actions.proposalsChanged();
                    }, "Proposta di creazione del file pronta: la trovi in «Proposte».")}>
              Proponi la creazione del file
            </button>
          )}
          {editable && (d.origin === "MANUAL" || d.pathState === "MISSING") && (
            <button type="button" className="btn btn-small btn-danger" disabled={busy}
                    onClick={() => {
                      if (!window.confirm(`Eliminare il blocco «${d.name}» e le sue relazioni dal Blueprint? Nessun file viene toccato.`)) return;
                      run(async () => { await api.deleteBlueprintNode(projectId, d.id, etag); onClose(); });
                    }}>
              Elimina blocco
            </button>
          )}
        </div>
        {editable && (
          <div className="row" style={{ marginTop: 8 }}>
            <select aria-label="Azione AI" value={action} onChange={(e) => setAction(e.target.value as AiAction)} style={{ flex: 1 }}>
              {vocabulary.actions.map((a) => <option key={a.action} value={a.action} title={a.description}>{a.label}</option>)}
            </select>
            <button type="button" className="btn btn-small" disabled={busy}
                    onClick={() => run(async () => {
                      const outcome = await api.blueprintAiAction(projectId, d.id, action);
                      api.blueprintAiActions(projectId, d.id).then(setOutcomes).catch(() => undefined);
                      setNote(`Task creata: «${outcome.taskTitle}». L'orchestratore la assegna; il risultato passa dalla review.`);
                      actions.openTask(outcome.taskId);
                    })}>
              Chiedi all'AI
            </button>
          </div>
        )}
      </Section>
    </aside>
  );
}

/** The Inspector of a relation: what it means, where the code says it, what can be done with it. */
export function RelationInspector({ projectId, relation, graph, vocabulary, editable, actions, onClose }: {
  projectId: number; relation: BlueprintRelation; graph: BlueprintGraph; vocabulary: BlueprintVocabulary; editable: boolean;
  actions: InspectorActions; onClose: () => void;
}) {
  const api = useApi();
  const [type, setType] = useState<RelationType>(relation.relation);
  const [label, setLabel] = useState(relation.label ?? "");
  const [description, setDescription] = useState(relation.description ?? "");
  const [problem, setProblem] = useState<unknown>(null);
  const [note, setNote] = useState<string | null>(null);
  useEffect(() => {
    setType(relation.relation); setLabel(relation.label ?? ""); setDescription(relation.description ?? ""); setNote(null);
  }, [relation]);
  const source = graph.nodes.find((n) => n.id === relation.source);
  const target = graph.nodes.find((n) => n.id === relation.target);
  const detected = relation.origin === "DETECTED";
  const info = vocabulary.relations.find((r) => r.relation === relation.relation);
  const dependency = !detected && ["USES", "CALLS", "SERVICE_TO_REPOSITORY"].includes(relation.relation)
    && source?.language === "java" && source.pathState === "LINKED";

  async function run(work: () => Promise<unknown>, done?: string) {
    setProblem(null); setNote(null);
    try { await work(); if (done) setNote(done); actions.changed(); } catch (error) { setProblem(error); }
  }

  return (
    <aside className="bp-inspector" aria-label="Inspector della relazione">
      <div className="bp-insp-head" style={{ ["--kind" as string]: RELATION_COLOR[relation.family] }}>
        <span className="bp-mono" aria-hidden>↦</span>
        <div style={{ minWidth: 0 }}>
          <h2>{info?.label ?? relation.relation}</h2>
          <div className="muted" style={{ fontSize: 12 }}>{ORIGIN_LABEL[relation.origin]}</div>
        </div>
        <span className="spacer" />
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi l'Inspector">×</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {note && <div className="notice">{note}</div>}
      <Section title="Relazione">
        <div className="bp-kv">
          <dt>Da</dt><dd><button type="button" className="bp-link" onClick={() => source && actions.focus([source.id], [relation.id])}>{source?.name}</button></dd>
          <dt>A</dt><dd><button type="button" className="bp-link" onClick={() => target && actions.focus([target.id], [relation.id])}>{target?.name}</button></dd>
          <dt>Tipo</dt>
          <dd>{editable && !detected
            ? <select aria-label="Tipo di relazione" value={type} onChange={(e) => setType(e.target.value as RelationType)}>
                {vocabulary.relations.map((r) => <option key={r.relation} value={r.relation}>{r.label}</option>)}
              </select>
            : <>{info?.label} <span className="muted">— {info?.description}</span></>}</dd>
          <dt>Etichetta</dt>
          <dd>{editable ? <input aria-label="Etichetta" value={label} onChange={(e) => setLabel(e.target.value)} /> : relation.label ?? "—"}</dd>
          <dt>Descrizione</dt>
          <dd>{editable ? <textarea aria-label="Descrizione della relazione" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
            : relation.description ?? "—"}</dd>
          <dt>Codice</dt>
          <dd>{relation.code
            ? <><span className="mono bp-break">{relation.code.path}{relation.code.line ? `:${relation.code.line}` : ""}</span>
                {relation.code.excerpt && <pre className="bp-pseudo">{relation.code.excerpt}</pre>}</>
            : <span className="muted">{detected ? "—" : "Non ancora nel codice."}</span>}</dd>
        </div>
      </Section>
      {editable && (
        <div className="bp-actions">
          <button className="btn btn-primary btn-small"
                  disabled={type === relation.relation && label === (relation.label ?? "") && description === (relation.description ?? "")}
                  onClick={() => run(() => api.updateBlueprintEdge(projectId, relation.id, relation.version,
                    { relation: type, label: label || undefined, description: description || undefined }), "Relazione salvata.")}>
            Salva
          </button>
          {relation.origin === "PROPOSED" && (
            <button className="btn btn-small" onClick={() => run(() => api.confirmBlueprintEdge(projectId, relation.id, relation.version),
              "Confermata: ora è una relazione manuale.")}>Conferma</button>
          )}
          {dependency && (
            <button className="btn btn-small" onClick={() => run(async () => {
              await api.proposeBlueprintChange(projectId, { kind: "ADD_DEPENDENCY", edgeId: relation.id });
              actions.proposalsChanged();
            }, "Proposed Code Change pronta: il diff è in «Proposte». Il codice non è stato toccato.")}>
              Proponi la modifica al codice
            </button>
          )}
          {!detected && (
            <button className="btn btn-small btn-danger" onClick={() => {
              if (window.confirm("Eliminare questa relazione dal Blueprint?")) {
                run(async () => { await api.deleteBlueprintEdge(projectId, relation.id, relation.version); onClose(); });
              }
            }}>{relation.origin === "PROPOSED" ? "Rifiuta" : "Elimina"}</button>
          )}
        </div>
      )}
    </aside>
  );
}

function Connections({ title, items, arrow, relationLabel, onFocus }: {
  title: string; items: BlueprintConnection[]; arrow: string; relationLabel: (r: RelationType) => string;
  onFocus: (nodeId: number, edgeId: number) => void;
}) {
  return (
    <div style={{ marginBottom: 8 }}>
      <div className="bp-subtitle">{title} ({items.length})</div>
      {items.length === 0 && <div className="muted" style={{ fontSize: 12 }}>—</div>}
      {items.map((c) => (
        <button key={c.edgeId} type="button" className={`bp-conn is-${c.origin.toLowerCase()}`} onClick={() => onFocus(c.nodeId, c.edgeId)}
                title={`${ORIGIN_LABEL[c.origin]}${c.label ? ` — ${c.label}` : ""}`}>
          <span className="bp-conn-rel">{arrow} {relationLabel(c.relation)}</span>
          <span className="bp-conn-name" style={{ color: KIND_LOOK[c.nodeKind].color }}>{c.nodeName}</span>
        </button>
      ))}
    </div>
  );
}

export function CodeLines({ snippet, highlight }: { snippet: BlueprintSnippet; highlight?: [number, number] }) {
  const lines = snippet.text.split("\n");
  return (
    <div className="bp-codeview" role="region" aria-label={`Codice di ${snippet.path}`}>
      {lines.map((l, i) => {
        const n = snippet.startLine + i;
        const hot = highlight && n >= highlight[0] && n <= highlight[1];
        return <div key={n} id={hot && highlight && n === highlight[0] ? "bp-first-hot" : undefined} className={hot ? "bp-line is-hot" : "bp-line"}>
          <span className="bp-ln">{n}</span><code>{l || " "}</code></div>;
      })}
      {snippet.truncated && <div className="muted" style={{ fontSize: 11.5, padding: 4 }}>… (estratto troncato)</div>}
    </div>
  );
}

function Section({ title, extra, open = true, children }: { title: string; extra?: ReactNode; open?: boolean; children: ReactNode }) {
  return (
    <details className="bp-section" open={open}>
      <summary><span>{title}</span>{extra}</summary>
      <div className="bp-section-body">{children}</div>
    </details>
  );
}
