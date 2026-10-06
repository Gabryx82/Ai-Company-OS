import { useEffect, useMemo, useState } from "react";
import { useApi } from "../../context";
import type {
  BlueprintBlock, BlueprintProposal, BlueprintSnippet, BlueprintVocabulary, EdgeOrigin, NodeKind, PathState, RelationType,
} from "../../api/types";
import { Field, Modal, ProblemNote, when } from "../../components/ui";
import { diffLines, search } from "./blueprintModel";
import { KIND_LOOK, PATH_STATE_LABEL } from "./blueprintStyle";
import { CodeLines } from "./Inspector";

/**
 * + Add Block (directive §5): kind, name, path, description, language,
 * pseudocode, notes, tags. A path that names a real file links the block to it;
 * otherwise the operator says what it is. No file is ever created here.
 */
export function AddBlockDialog({ projectId, vocabulary, at, onClose, onCreated }: {
  projectId: number; vocabulary: BlueprintVocabulary; at: { x: number; y: number };
  onClose: () => void; onCreated: (id: number) => void;
}) {
  const api = useApi();
  const [kind, setKind] = useState<NodeKind>("SERVICE");
  const [name, setName] = useState("");
  const [filePath, setFilePath] = useState("");
  const [pathState, setPathState] = useState<PathState>("TO_CREATE");
  const [language, setLanguage] = useState("");
  const [description, setDescription] = useState("");
  const [pseudocode, setPseudocode] = useState("");
  const [notes, setNotes] = useState("");
  const [tags, setTags] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true); setProblem(null);
    try {
      const { body } = await api.createBlueprintNode(projectId, {
        kind, name, filePath: filePath || undefined, pathState: filePath ? pathState : undefined, language: language || undefined,
        description: description || undefined, pseudocode: pseudocode || undefined, notes: notes || undefined,
        tags: tags.split(",").map((t) => t.trim()).filter(Boolean), x: at.x, y: at.y,
      });
      onCreated(body.id);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title="Nuovo blocco" onClose={onClose} wide>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="grid grid-2" style={{ gap: 10 }}>
        <Field label="Tipo">
          <select value={kind} onChange={(e) => setKind(e.target.value as NodeKind)}>
            {vocabulary.kinds.map((k) => <option key={k.kind} value={k.kind}>{k.label}</option>)}
          </select>
        </Field>
        <Field label="Nome"><input value={name} onChange={(e) => setName(e.target.value)} placeholder="Retriever" autoFocus /></Field>
        <Field label="Percorso file" hint="Se esiste nel progetto, il blocco vi si collega. Nessun file viene creato.">
          <input className="mono" value={filePath} onChange={(e) => setFilePath(e.target.value)} placeholder="backend/src/main/java/…/Retriever.java" />
        </Field>
        <Field label="Linguaggio" hint="Vuoto: dedotto dall'estensione, se il file esiste.">
          <input value={language} onChange={(e) => setLanguage(e.target.value)} placeholder="java, typescript, python…" />
        </Field>
      </div>
      {filePath && (
        <fieldset className="bp-fieldset">
          <legend>Se il file non esiste ancora, è…</legend>
          {(["TO_CREATE", "DOCUMENT_REFERENCE", "VIRTUAL"] as PathState[]).map((p) => (
            <label key={p} className="row" style={{ gap: 6 }}>
              <input type="radio" name="pathState" style={{ width: "auto" }} checked={pathState === p} onChange={() => setPathState(p)} />
              {PATH_STATE_LABEL[p]}
            </label>
          ))}
        </fieldset>
      )}
      <Field label="Descrizione"><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)}
                                           placeholder="Recupera documenti rilevanti dalla knowledge base." /></Field>
      <Field label="Pseudocodice"><textarea className="mono" rows={4} value={pseudocode} onChange={(e) => setPseudocode(e.target.value)}
                                            placeholder={"INPUT query\nCALL index.search(query)\nRETURN documents"} /></Field>
      <div className="grid grid-2" style={{ gap: 10 }}>
        <Field label="Note"><input value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
        <Field label="Tag" hint="Separati da virgola."><input value={tags} onChange={(e) => setTags(e.target.value)} placeholder="rag, core" /></Field>
      </div>
      <div className="row modal-actions">
        <span className="spacer" />
        <button className="btn" onClick={onClose}>Annulla</button>
        <button className="btn btn-primary" disabled={busy || !name.trim()} onClick={create}>{busy ? "Creo…" : "Crea blocco"}</button>
      </div>
    </Modal>
  );
}

/**
 * A relation drawn between two ports, or started from the Inspector (directive
 * §8): its type, a label, a description, and whether it is the operator's
 * decision or only a proposal.
 */
export function RelationDialog({ projectId, vocabulary, blocks, source, target, onClose, onCreated }: {
  projectId: number; vocabulary: BlueprintVocabulary; blocks: BlueprintBlock[]; source: number; target: number | null;
  onClose: () => void; onCreated: () => void;
}) {
  const api = useApi();
  const [to, setTo] = useState<number | null>(target);
  const [query, setQuery] = useState("");
  const [relation, setRelation] = useState<RelationType>("USES");
  const [origin, setOrigin] = useState<EdgeOrigin>("MANUAL");
  const [label, setLabel] = useState("");
  const [description, setDescription] = useState("");
  const [problem, setProblem] = useState<unknown>(null);
  const from = blocks.find((b) => b.id === source);
  const destination = blocks.find((b) => b.id === to);
  const found = useMemo(() => search(blocks.filter((b) => b.id !== source), query, 8), [blocks, source, query]);
  const families = useMemo(() => [...new Set(vocabulary.relations.map((r) => r.family))], [vocabulary]);

  async function create() {
    if (to === null) return;
    setProblem(null);
    try {
      await api.createBlueprintEdge(projectId, { sourceId: source, targetId: to, relation, origin,
        label: label || undefined, description: description || undefined });
      onCreated();
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <Modal title="Nuova relazione" onClose={onClose}>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="bp-relation-preview">
        <span style={{ color: from ? KIND_LOOK[from.kind].color : undefined }}>{from?.name}</span>
        <span className="muted"> → {vocabulary.relations.find((r) => r.relation === relation)?.label} → </span>
        <span style={{ color: destination ? KIND_LOOK[destination.kind].color : undefined }}>{destination?.name ?? "?"}</span>
      </div>
      {target === null && (
        <Field label="Verso il blocco">
          <div>
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Cerca per nome o percorso" autoFocus />
            <div className="bp-search-list">
              {found.map((b) => (
                <button key={b.id} type="button" className={b.id === to ? "is-active" : ""} onClick={() => setTo(b.id)}>
                  <span className="bp-mono-sm" style={{ background: KIND_LOOK[b.kind].color }}>{KIND_LOOK[b.kind].mono}</span> {b.name}
                </button>
              ))}
            </div>
          </div>
        </Field>
      )}
      <Field label="Tipo di relazione">
        <select value={relation} onChange={(e) => setRelation(e.target.value as RelationType)}>
          {families.map((f) => (
            <optgroup key={f} label={f}>
              {vocabulary.relations.filter((r) => r.family === f).map((r) => <option key={r.relation} value={r.relation} title={r.description}>{r.label}</option>)}
            </optgroup>
          ))}
        </select>
      </Field>
      <Field label="Etichetta"><input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="find(), save()" /></Field>
      <Field label="Descrizione"><textarea rows={2} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
      <div className="row" style={{ gap: 14 }}>
        <label className="row" style={{ gap: 6 }}><input type="radio" style={{ width: "auto" }} checked={origin === "MANUAL"}
                                                         onChange={() => setOrigin("MANUAL")} /> Manuale (decisa)</label>
        <label className="row" style={{ gap: 6 }}><input type="radio" style={{ width: "auto" }} checked={origin === "PROPOSED"}
                                                         onChange={() => setOrigin("PROPOSED")} /> Proposta (da confermare)</label>
      </div>
      <div className="muted" style={{ fontSize: 12 }}>La relazione vive nel Blueprint: per portarla nel codice, dall'Inspector «Proponi la modifica al codice».</div>
      <div className="row modal-actions"><span className="spacer" />
        <button className="btn" onClick={onClose}>Annulla</button>
        <button className="btn btn-primary" disabled={to === null} onClick={create}>Crea relazione</button>
      </div>
    </Modal>
  );
}

/**
 * Proposed Code Changes (directive §9): what the Blueprint would change in a
 * real file, as a diff, with the reason and the consequences. Accepting creates
 * the task that will do it through the governance workflow; nothing is applied.
 */
export function ProposalsDialog({ projectId, onClose, onOpenTask, onChanged }: {
  projectId: number; onClose: () => void; onOpenTask: (taskId: number) => void; onChanged: () => void;
}) {
  const api = useApi();
  const [proposals, setProposals] = useState<BlueprintProposal[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const load = () => api.blueprintProposals(projectId).then((p) => { setProposals(p); setOpen((o) => o ?? p.find((x) => x.status === "PROPOSED")?.id ?? null); })
    .catch(setProblem);
  useEffect(() => { load(); }, [projectId]); // eslint-disable-line react-hooks/exhaustive-deps

  async function decide(p: BlueprintProposal, accept: boolean) {
    setProblem(null);
    try {
      const decided = await api.decideBlueprintProposal(projectId, p.id, p.version, accept);
      await load();
      onChanged();
      if (accept && decided.taskId) onOpenTask(decided.taskId);
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <Modal title="Proposed Code Changes" onClose={onClose} wide>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="muted" style={{ fontSize: 12.5 }}>
        Il Blueprint non modifica mai il codice. Accettare una proposta crea una task del progetto con il diff: la esegue un agente
        (o un handoff), e l'esito passa dalla review.
      </div>
      {proposals === null && <div className="muted">Carico…</div>}
      {proposals?.length === 0 && <div className="muted">Nessuna proposta. Nascono dall'Inspector: un blocco «da creare», o una relazione manuale fra classi Java.</div>}
      <div className="stack" style={{ gap: 8, maxHeight: "62vh", overflow: "auto" }}>
        {proposals?.map((p) => (
          <div key={p.id} className="bp-proposal">
            <button type="button" className="bp-proposal-head" onClick={() => setOpen(open === p.id ? null : p.id)} aria-expanded={open === p.id}>
              <span className={p.status === "PROPOSED" ? "badge badge-warn" : p.status === "ACCEPTED" ? "badge badge-ok" : "badge"}>
                {p.status === "PROPOSED" ? "Da decidere" : p.status === "ACCEPTED" ? "Accettata" : "Rifiutata"}</span>
              <strong>{p.title}</strong>
              <span className="mono muted" style={{ fontSize: 11.5 }}>{p.filePath}</span>
              <span className="spacer" />
              <span className="muted" style={{ fontSize: 11.5 }}>{when(p.createdAt)}</span>
            </button>
            {open === p.id && (
              <div className="stack" style={{ gap: 8, padding: "8px 10px 10px" }}>
                {p.reason && <div><b>Motivo.</b> {p.reason}</div>}
                {p.consequences && <div><b>Conseguenze.</b> {p.consequences}</div>}
                <pre className="bp-diff" aria-label="Diff proposto">
                  {diffLines(p.diff).map((l, i) => <div key={i} className={`bp-diff-${l.kind}`}>{l.text || " "}</div>)}
                </pre>
                <div className="row">
                  {p.status === "PROPOSED" && <>
                    <button className="btn btn-primary btn-small" onClick={() => decide(p, true)}>Accetta: crea la task</button>
                    <button className="btn btn-small btn-danger" onClick={() => decide(p, false)}>Rifiuta</button>
                  </>}
                  {p.taskId && <button className="btn btn-small" onClick={() => onOpenTask(p.taskId!)}>Apri la task</button>}
                  {p.decidedBy && <span className="muted" style={{ fontSize: 12 }}>deciso da {p.decidedBy}, {when(p.decidedAt)}</span>}
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </Modal>
  );
}

/** Open file (directive §10): the whole file, read-only, with the component's lines highlighted. */
export function FileViewer({ projectId, block, onClose }: { projectId: number; block: BlueprintBlock; onClose: () => void }) {
  const api = useApi();
  const [snippet, setSnippet] = useState<BlueprintSnippet | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [range, setRange] = useState<[number, number] | undefined>();
  useEffect(() => {
    api.blueprintSnippet(projectId, block.id, true).then(setSnippet).catch(setProblem);
    api.blueprintNode(projectId, block.id).then(({ body }) => {
      if (body.startLine) setRange([body.startLine, body.endLine ?? body.startLine]);
    }).catch(() => undefined);
  }, [api, projectId, block.id]);
  useEffect(() => {
    if (snippet && range) document.getElementById("bp-first-hot")?.scrollIntoView({ block: "center" });
  }, [snippet, range]);
  return (
    <Modal title={block.filePath ?? block.name} onClose={onClose} wide>
      <ProblemNote problem={problem} />
      {!snippet && !problem && <div className="muted">Carico il file…</div>}
      {snippet && <div className="bp-fileview"><CodeLines snippet={snippet} highlight={range} /></div>}
    </Modal>
  );
}
