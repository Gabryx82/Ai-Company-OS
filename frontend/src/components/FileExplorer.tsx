import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { useApi } from "../context";
import type { Project, ProjectDirectoryListing, ProjectFileEntry, ProjectFileView } from "../api/types";
import { Icon } from "./icons";
import { ProblemNote } from "./ui";

/** A file the viewer shows: which project, and its path inside the project folder. */
export type OpenedFile = { projectId: number; path: string; projectName?: string };

type Folder = { listing: ProjectDirectoryListing } | { problem: unknown } | "loading";

const PROJECT_KEY = "aicos.files.project";

function remembered(): number | null {
  try {
    const value = Number(localStorage.getItem(PROJECT_KEY));
    return Number.isFinite(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

function remember(id: number) {
  try {
    localStorage.setItem(PROJECT_KEY, String(id));
  } catch {
    // a private window: the choice lasts as long as the page
  }
}

function size(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * PHASE 33 (ADR-039): every file of a project's folder, as a tree on the left of the
 * console. Read-only: folders open one at a time, as they are expanded; a file opens
 * in the viewer.
 */
export function FileExplorer({ projectHint, opened, onOpen, onClose }: {
  projectHint: number | null; opened: OpenedFile | null; onOpen: (file: OpenedFile) => void; onClose: () => void;
}) {
  const api = useApi();
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [chosen, setChosen] = useState<number | null>(null);
  const [folders, setFolders] = useState<Record<string, Folder>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState("");

  useEffect(() => {
    api.projects().then(setProjects).catch(setProblem);
  }, [api]);

  const browsable = useMemo(() => (projects ?? []).filter((p) => p.workspacePath), [projects]);
  // A project chosen in the panel wins; otherwise the project of the page in view, then the last one chosen.
  const projectId = useMemo(() => {
    const ids = browsable.map((p) => p.id);
    for (const candidate of [chosen, projectHint, remembered()]) {
      if (candidate !== null && ids.includes(candidate)) return candidate;
    }
    return ids[0] ?? null;
  }, [browsable, chosen, projectHint]);

  const current = browsable.find((p) => p.id === projectId);

  const load = useCallback((path: string) => {
    if (projectId === null) return;
    setFolders((all) => ({ ...all, [path]: "loading" }));
    api.browse(projectId, path)
      .then((listing) => setFolders((all) => ({ ...all, [path]: { listing } })))
      .catch((error) => setFolders((all) => ({ ...all, [path]: { problem: error } })));
  }, [api, projectId]);

  useEffect(() => {
    setFolders({});
    setExpanded(new Set());
    if (projectId !== null) load("");
  }, [projectId, load]);

  const toggle = (entry: ProjectFileEntry) => {
    const next = new Set(expanded);
    if (next.has(entry.path)) {
      next.delete(entry.path);
    } else {
      next.add(entry.path);
      const folder = folders[entry.path];
      if (!folder || (typeof folder === "object" && "problem" in folder)) load(entry.path);
    }
    setExpanded(next);
  };

  const refresh = () => {
    setFolders({});
    load("");
    expanded.forEach((path) => load(path));
  };

  const choose = (id: number) => {
    setChosen(id);
    remember(id);
  };

  const needle = filter.trim().toLowerCase();

  const renderFolder = (path: string, depth: number): ReactNode => {
    const folder = folders[path];
    if (!folder) return null;
    if (folder === "loading") {
      return <div className="ft-note muted" style={{ paddingLeft: 12 + depth * 14 }}>Carico…</div>;
    }
    if ("problem" in folder) {
      return <div className="ft-note" style={{ paddingLeft: 12 + depth * 14 }}><ProblemNote problem={folder.problem} /></div>;
    }
    const entries = folder.listing.entries.filter((e) =>
      !needle || e.name.toLowerCase().includes(needle) || (e.kind !== "FILE" && expanded.has(e.path)));
    return (
      <>
        {entries.map((entry) => {
          const isFolder = entry.kind !== "FILE";
          const open = expanded.has(entry.path);
          const isCurrent = opened?.projectId === projectId && opened.path === entry.path;
          return (
            <div key={entry.path} role="treeitem" aria-expanded={isFolder ? open : undefined} aria-selected={isCurrent}>
              <button className={isCurrent ? "ft-row is-current" : "ft-row"} style={{ paddingLeft: 8 + depth * 14 }}
                      title={entry.kind === "FILE" ? `${entry.path} · ${size(entry.size)}` : entry.path}
                      onClick={() => (isFolder ? toggle(entry) : onOpen({ projectId: projectId!, path: entry.path, projectName: current?.name }))}>
                <span className={open ? "ft-chevron is-open" : "ft-chevron"} aria-hidden="true">
                  {isFolder && <Icon name="chevron" size={12} />}
                </span>
                <Icon name={entry.kind === "LINK" ? "link" : isFolder ? "folder" : "file"} size={14}
                      className={isFolder ? "ft-icon-folder" : "ft-icon-file"} />
                <span className="ft-name">{entry.name}</span>
              </button>
              {isFolder && open && <div role="group">{renderFolder(entry.path, depth + 1)}</div>}
            </div>
          );
        })}
        {folder.listing.truncated && (
          <div className="ft-note muted" style={{ paddingLeft: 12 + depth * 14 }}>Solo le prime 5000 voci</div>
        )}
        {entries.length === 0 && (
          <div className="ft-note muted" style={{ paddingLeft: 12 + depth * 14 }}>{needle ? "Nessuna corrispondenza" : "Cartella vuota"}</div>
        )}
      </>
    );
  };

  return (
    <aside className="file-panel" aria-label="File dei progetti">
      <div className="file-panel-head">
        <strong><Icon name="files" size={15} /> File</strong>
        <span className="spacer" />
        <button className="btn btn-small btn-icon" onClick={refresh} title="Rileggi le cartelle" aria-label="Rileggi le cartelle"
                disabled={projectId === null}><Icon name="refresh" size={13} /></button>
        <button className="btn btn-small btn-icon" onClick={onClose} title="Chiudi il pannello" aria-label="Chiudi il pannello file">
          <Icon name="close" size={13} /></button>
      </div>
      <ProblemNote problem={problem} />
      {projects !== null && browsable.length === 0 && (
        <p className="muted ft-empty">Nessun progetto ha una cartella: impostala nel profilo del progetto.</p>
      )}
      {browsable.length > 0 && (
        <div className="file-panel-tools">
          <select aria-label="Progetto" value={projectId ?? ""} onChange={(e) => choose(Number(e.target.value))}>
            {browsable.map((p) => <option key={p.id} value={p.id}>{p.name}{p.status === "ARCHIVED" ? " (archiviato)" : ""}</option>)}
          </select>
          <input type="search" placeholder="Filtra i nomi aperti…" aria-label="Filtra i file" value={filter}
                 onChange={(e) => setFilter(e.target.value)} />
          {current && <div className="mono muted ft-root" title={current.workspacePath ?? ""}>{current.workspacePath}</div>}
        </div>
      )}
      <div className="file-tree" role="tree" aria-label="Cartella del progetto">
        {projectId !== null && renderFolder("", 0)}
      </div>
    </aside>
  );
}

/** PHASE 33 (ADR-039): one file of a project, read-only, where the page was. */
export function FileViewer({ file, onClose }: { file: OpenedFile; onClose: () => void }) {
  const api = useApi();
  const [view, setView] = useState<ProjectFileView | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [image, setImage] = useState<string | null>(null);
  const [wrap, setWrap] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let alive = true;
    setView(null);
    setProblem(null);
    api.browseFile(file.projectId, file.path)
      .then((v) => { if (alive) setView(v); })
      .catch((e) => { if (alive) setProblem(e); });
    return () => { alive = false; };
  }, [api, file.projectId, file.path]);

  useEffect(() => {
    if (view?.kind !== "IMAGE") return;
    let url: string | null = null;
    let alive = true;
    api.browseImage(file.projectId, file.path)
      .then((u) => { url = u; if (alive) setImage(u); else URL.revokeObjectURL(u); })
      .catch((e) => { if (alive) setProblem(e); });
    return () => {
      alive = false;
      if (url) URL.revokeObjectURL(url);
      setImage(null);
    };
  }, [api, view, file.projectId, file.path]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const copyPath = () => {
    navigator.clipboard?.writeText(file.path).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }).catch(() => undefined);
  };

  const lines = view?.content !== null && view?.content !== undefined ? view.content.replace(/\r\n?/g, "\n").split("\n") : [];
  if (lines.length > 1 && lines[lines.length - 1] === "") lines.pop();

  return (
    <section className="file-viewer" aria-label={`File ${file.path}`}>
      <div className="file-viewer-head">
        <div className="file-viewer-title">
          <small className="muted">{file.projectName ?? "Progetto"}</small>
          <strong className="mono">{file.path}</strong>
        </div>
        <span className="spacer" />
        {view && <span className="muted">{size(view.size)}{view.modifiedAt ? ` · ${new Date(view.modifiedAt).toLocaleString("it-IT")}` : ""}</span>}
        {view?.kind === "TEXT" && (
          <button className="btn btn-small" aria-pressed={wrap} onClick={() => setWrap((w) => !w)}>A capo</button>
        )}
        <button className="btn btn-small" onClick={copyPath}><Icon name="copy" size={13} /> {copied ? "Copiato" : "Copia percorso"}</button>
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi il file"><Icon name="close" size={13} /> Chiudi</button>
      </div>
      <ProblemNote problem={problem} />
      {!view && !problem && <p className="muted" style={{ padding: 16 }}>Carico…</p>}
      {view?.kind === "TEXT" && (
        lines.length === 0 || (lines.length === 1 && lines[0] === "")
          ? <p className="muted" style={{ padding: 16 }}>File vuoto.</p>
          : (
            <div className={wrap ? "file-code is-wrapped" : "file-code"}>
              {!wrap && <pre className="file-code-lines" aria-hidden="true">{lines.map((_, i) => i + 1).join("\n")}</pre>}
              <pre className="file-code-text">{lines.join("\n")}</pre>
            </div>
          )
      )}
      {view?.kind === "IMAGE" && (
        <div className="file-image">{image ? <img src={image} alt={view.name} /> : <p className="muted">Carico l'immagine…</p>}</div>
      )}
      {view && (view.kind === "WITHHELD" || view.kind === "BINARY" || view.kind === "TOO_LARGE") && (
        <div className={view.kind === "WITHHELD" ? "notice notice-warn file-notice" : "notice file-notice"}>
          <Icon name={view.kind === "WITHHELD" ? "warning" : "file"} size={14} /> {view.reason}
        </div>
      )}
    </section>
  );
}
