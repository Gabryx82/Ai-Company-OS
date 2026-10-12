import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent, type ReactNode } from "react";
import { useApi } from "../context";
import type { Project, ProjectDirectoryListing, ProjectFileEntry, ProjectFileView, Software } from "../api/types";
import { AppIcon } from "./AppIcon";
import { ContextMenu, type MenuEntry } from "./ContextMenu";
import { alreadyGone, ConfirmDeletion } from "./Deletion";
import { Icon } from "./icons";
import { OpenWithButton, openWithEntries, useFileOpeners, useOpenFileWith } from "./OpenWith";
import { openWith, shortName } from "./fileOpeners";
import { ProblemNote } from "./ui";

/** A file the viewer shows: which project, and its path inside the project folder. */
export type OpenedFile = { projectId: number; path: string; projectName?: string };

type Folder = { listing: ProjectDirectoryListing } | { problem: unknown } | "loading";

/** Where a context menu is, and for what: an entry of the tree, or the project folder itself (null). */
type MenuState = { x: number; y: number; target: ProjectFileEntry | null; mode: "main" | "openWith" };

type Creating = { parent: string; kind: "FILE" | "DIRECTORY" };

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

function parentOf(path: string): string {
  const slash = path.lastIndexOf("/");
  return slash < 0 ? "" : path.slice(0, slash);
}

function under(path: string, folder: string): boolean {
  return path === folder || path.startsWith(`${folder}/`);
}

/** The path as Windows writes it, under the project's folder. */
function absolute(root: string, relative: string): string {
  const separator = root.includes("\\") ? "\\" : "/";
  const base = root.endsWith(separator) ? root.slice(0, -1) : root;
  return relative ? `${base}${separator}${relative.split("/").join(separator)}` : base;
}

/**
 * ADR-040: a name typed in the tree, the way file managers do it -- Enter keeps it,
 * Escape gives up, leaving the field keeps it too, unless the last attempt was refused.
 * For a file, the name without its extension is selected.
 */
function InlineName({ initial, label, file, onCommit, onCancel }: {
  initial: string; label: string; file: boolean;
  onCommit: (value: string) => Promise<void>; onCancel: () => void;
}) {
  const [value, setValue] = useState(initial);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<unknown>(null);
  const finished = useRef(false);

  const commit = async () => {
    if (finished.current || busy) return;
    const name = value.trim();
    if (!name || name === initial) {
      finished.current = true;
      onCancel();
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      await onCommit(name);
      finished.current = true;
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  };

  const cancel = () => {
    finished.current = true;
    onCancel();
  };

  return (
    <>
      <input className="ft-input" aria-label={label} value={value} disabled={busy} autoFocus spellCheck={false}
             onChange={(event) => { setValue(event.target.value); setProblem(null); }}
             onFocus={(event) => {
               const dot = event.target.value.lastIndexOf(".");
               event.target.setSelectionRange(0, file && dot > 0 ? dot : event.target.value.length);
             }}
             onKeyDown={(event) => {
               if (event.key === "Enter") { event.preventDefault(); void commit(); }
               if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); cancel(); }
             }}
             onBlur={() => { if (problem) cancel(); else void commit(); }} />
      {problem !== null && <div className="ft-input-problem"><ProblemNote problem={problem} /></div>}
    </>
  );
}

/**
 * PHASE 33 (ADR-039): every file of a project's folder, as a tree on the left of the
 * console. Folders open one at a time, as they are expanded; a file opens in the viewer.
 *
 * ADR-040: a right click (or the context-menu key, or Shift+F10) on a file or a folder
 * opens its menu: open, «Apri con» the program of the Software Hub that suits it,
 * Explorer, the paths, rename (F2), a new file or folder, and the Recycle Bin (Canc).
 */
export function FileExplorer({ projectHint, opened, onOpen, onClose, onOpenedChange }: {
  projectHint: number | null; opened: OpenedFile | null; onOpen: (file: OpenedFile) => void; onClose: () => void;
  /** ADR-040: the file in the viewer was renamed (a new path) or went to the Recycle Bin (null). */
  onOpenedChange?: (file: OpenedFile | null) => void;
}) {
  const api = useApi();
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [chosen, setChosen] = useState<number | null>(null);
  const [folders, setFolders] = useState<Record<string, Folder>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState("");
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const [creating, setCreating] = useState<Creating | null>(null);
  const [recycling, setRecycling] = useState<ProjectFileEntry | null>(null);
  const [notice, setNotice] = useState<{ text: string; detail?: string } | null>(null);
  const [actionProblem, setActionProblem] = useState<unknown>(null);
  // The Software Hub is read the first time a menu opens, not every time the panel does.
  const [wantOpeners, setWantOpeners] = useState(false);
  const { software, problem: openersProblem } = useFileOpeners(wantOpeners);
  const launcher = useOpenFileWith();

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
  const archived = current?.status === "ARCHIVED";

  /** Reads a folder; `quiet` keeps what is shown until the new listing arrives. */
  const load = useCallback((path: string, quiet = false) => {
    if (projectId === null) return;
    setFolders((all) => (quiet && all[path] && typeof all[path] === "object" && "listing" in all[path]
      ? all : { ...all, [path]: "loading" }));
    api.browse(projectId, path)
      .then((listing) => setFolders((all) => ({ ...all, [path]: { listing } })))
      .catch((error) => setFolders((all) => ({ ...all, [path]: { problem: error } })));
  }, [api, projectId]);

  useEffect(() => {
    setFolders({});
    setExpanded(new Set());
    setMenu(null);
    setRenaming(null);
    setCreating(null);
    if (projectId !== null) load("");
  }, [projectId, load]);

  // A notice says what just happened, then goes.
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 5000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  useEffect(() => {
    if (launcher.outcome) setNotice({ text: launcher.outcome.text, detail: launcher.outcome.command });
    if (launcher.problem) setActionProblem(launcher.problem);
  }, [launcher.outcome, launcher.problem]);

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

  const say = (text: string, detail?: string) => {
    setActionProblem(null);
    setNotice({ text, detail });
  };

  // --- what the menu does ------------------------------------------------------------

  const openMenu = (x: number, y: number, target: ProjectFileEntry | null) => {
    setWantOpeners(true);
    setActionProblem(null);
    setMenu({ x, y, target, mode: "main" });
  };

  const openFile = (entry: ProjectFileEntry) => {
    if (projectId !== null) onOpen({ projectId, path: entry.path, projectName: current?.name });
  };

  const openFileWith = (program: Software, entry: ProjectFileEntry) => {
    if (projectId !== null) launcher.open(program, projectId, entry.path);
  };

  const copy = (text: string, what: string) => {
    if (!navigator.clipboard) {
      setActionProblem(new Error("Il browser non permette di scrivere negli appunti da questa pagina."));
      return;
    }
    navigator.clipboard.writeText(text).then(() => say(`${what} copiato`, text))
      .catch(() => setActionProblem(new Error("Il browser non ha concesso gli appunti.")));
  };

  const reveal = (path: string) => {
    if (projectId === null) return;
    api.revealEntry(projectId, path).then(() => say("Aperto in Esplora risorse")).catch(setActionProblem);
  };

  const startCreate = (parent: string, kind: Creating["kind"]) => {
    if (parent && !expanded.has(parent)) {
      setExpanded((all) => new Set(all).add(parent));
      if (!folders[parent] || (typeof folders[parent] === "object" && "problem" in folders[parent])) load(parent);
    }
    setRenaming(null);
    setCreating({ parent, kind });
  };

  const create = async (name: string) => {
    if (projectId === null || !creating) return;
    const made = await api.createEntry(projectId, creating.parent, name, creating.kind);
    setCreating(null);
    load(creating.parent, true);
    say(creating.kind === "FILE" ? `Creato il file ${made.name}` : `Creata la cartella ${made.name}`);
    if (made.kind === "FILE") onOpen({ projectId, path: made.path, projectName: current?.name });
  };

  /** A rename moves every path under the old one: what is expanded, what is cached, what is in the viewer. */
  const moved = (from: string, to: string) => {
    const shift = (path: string) => (under(path, from) ? to + path.slice(from.length) : path);
    const reopen = [...expanded].filter((path) => under(path, from)).map(shift);
    setExpanded((all) => new Set([...all].map(shift)));
    setFolders((all) => Object.fromEntries(Object.entries(all).filter(([path]) => !under(path, from))));
    reopen.forEach((path) => load(path));
    if (opened && opened.projectId === projectId && under(opened.path, from)) {
      onOpenedChange?.({ ...opened, path: shift(opened.path) });
    }
  };

  const rename = async (entry: ProjectFileEntry, name: string) => {
    if (projectId === null) return;
    const renamed = await api.renameEntry(projectId, entry.path, name);
    setRenaming(null);
    moved(entry.path, renamed.path);
    load(parentOf(entry.path), true);
    say(`Rinominato in ${renamed.name}`);
  };

  const recycled = (entry: ProjectFileEntry) => {
    setRecycling(null);
    setExpanded((all) => new Set([...all].filter((path) => !under(path, entry.path))));
    setFolders((all) => Object.fromEntries(Object.entries(all).filter(([path]) => !under(path, entry.path))));
    load(parentOf(entry.path), true);
    if (opened && opened.projectId === projectId && under(opened.path, entry.path)) onOpenedChange?.(null);
  };

  const previewRecycle = useCallback(
    () => api.entryDeletionPreview(projectId!, recycling!.path),
    [api, projectId, recycling]);

  const menuEntries = (state: MenuState): MenuEntry[] => {
    const entry = state.target;
    const locked = archived ? "Progetto archiviato: i suoi file si leggono, non si cambiano" : undefined;
    const root = current?.workspacePath ?? "";

    if (state.mode === "openWith" && entry) {
      const choice = software ? openWith(software, entry.path) : null;
      return [
        { key: "back", label: "Indietro", icon: "back", keepOpen: true, onSelect: () => setMenu({ ...state, mode: "main" }) },
        { separator: "back" },
        ...openWithEntries(choice, openersProblem, (program) => openFileWith(program, entry)),
      ];
    }

    const paths: MenuEntry[] = [
      { key: "reveal", label: "Mostra in Esplora risorse", icon: "folder", onSelect: () => reveal(entry?.path ?? "") },
      { key: "copy", label: "Copia percorso", icon: "copy", onSelect: () => copy(absolute(root, entry?.path ?? ""), "Percorso") },
    ];
    if (entry) {
      paths.push({ key: "copy-relative", label: "Copia percorso relativo", icon: "copy",
        onSelect: () => copy(entry.path, "Percorso relativo") });
    }
    const creation: MenuEntry[] = [
      { key: "new-file", label: "Nuovo file…", icon: "file-plus", disabled: archived, title: locked,
        onSelect: () => startCreate(entry?.path ?? "", "FILE") },
      { key: "new-folder", label: "Nuova cartella…", icon: "folder-plus", disabled: archived, title: locked,
        onSelect: () => startCreate(entry?.path ?? "", "DIRECTORY") },
    ];

    if (!entry) return [...creation, { separator: "paths" }, ...paths];

    const link = entry.kind === "LINK" ? "È un collegamento: si gestisce da Esplora risorse" : undefined;
    const changes: MenuEntry[] = [
      { key: "rename", label: "Rinomina…", icon: "pencil", aside: "F2", disabled: archived || !!link, title: locked ?? link,
        onSelect: () => { setCreating(null); setRenaming(entry.path); } },
      { key: "recycle", label: "Sposta nel Cestino", icon: "trash", aside: "Canc", danger: true,
        disabled: archived || !!link, title: locked ?? link, onSelect: () => setRecycling(entry) },
    ];

    if (entry.kind === "FILE") {
      const choice = software ? openWith(software, entry.path) : null;
      const suggested = choice?.suggested;
      const open: MenuEntry[] = [{ key: "open", label: "Apri", icon: "file", onSelect: () => openFile(entry) }];
      if (suggested) {
        open.push({ key: "open-suggested", label: <>Apri con {shortName(suggested.software)}</>,
          icon: <AppIcon software={suggested.software} size={16} />, hint: suggested.reason,
          onSelect: () => openFileWith(suggested.software, entry) });
      }
      open.push({ key: "open-with", label: "Apri con…", icon: "external", aside: "›", keepOpen: true,
        hint: !software ? (openersProblem ? "Il Software Hub non risponde" : "Leggo i programmi…") : undefined,
        onSelect: () => setMenu({ ...state, mode: "openWith" }) });
      return [...open, { separator: "paths" }, ...paths, { separator: "changes" }, ...changes];
    }
    if (entry.kind === "DIRECTORY") {
      return [...creation, { separator: "paths" }, ...paths, { separator: "changes" }, ...changes];
    }
    return [...paths, { separator: "changes" }, ...changes];
  };

  const onRowKeyDown = (event: KeyboardEvent<HTMLButtonElement>, entry: ProjectFileEntry) => {
    if (event.key === "ContextMenu" || (event.key === "F10" && event.shiftKey)) {
      event.preventDefault();
      const box = event.currentTarget.getBoundingClientRect();
      openMenu(box.left + 24, box.bottom, entry);
    } else if (event.key === "F2" && !archived && entry.kind !== "LINK") {
      event.preventDefault();
      setCreating(null);
      setRenaming(entry.path);
    } else if (event.key === "Delete" && !archived && entry.kind !== "LINK") {
      event.preventDefault();
      setRecycling(entry);
    }
  };

  const onRowContextMenu = (event: MouseEvent, entry: ProjectFileEntry) => {
    event.preventDefault();
    event.stopPropagation();
    openMenu(event.clientX, event.clientY, entry);
  };

  const needle = filter.trim().toLowerCase();

  const renderCreating = (parent: string, depth: number): ReactNode => creating?.parent === parent && (
    <div className="ft-row ft-editing" style={{ paddingLeft: 8 + depth * 14 }}>
      <span className="ft-chevron" aria-hidden="true" />
      <Icon name={creating.kind === "DIRECTORY" ? "folder" : "file"} size={14}
            className={creating.kind === "DIRECTORY" ? "ft-icon-folder" : "ft-icon-file"} />
      <InlineName initial="" file={false} onCommit={create} onCancel={() => setCreating(null)}
                  label={creating.kind === "DIRECTORY" ? "Nome della nuova cartella" : "Nome del nuovo file"} />
    </div>
  );

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
      !needle || e.name.toLowerCase().includes(needle) || (e.kind !== "FILE" && expanded.has(e.path))
      || e.path === renaming);
    return (
      <>
        {renderCreating(path, depth)}
        {entries.map((entry) => {
          const isFolder = entry.kind !== "FILE";
          const open = expanded.has(entry.path);
          const isCurrent = opened?.projectId === projectId && opened.path === entry.path;
          const icon = <Icon name={entry.kind === "LINK" ? "link" : isFolder ? "folder" : "file"} size={14}
                             className={isFolder ? "ft-icon-folder" : "ft-icon-file"} />;
          const classes = ["ft-row", isCurrent && "is-current", menu?.target?.path === entry.path && "is-menu"]
            .filter(Boolean).join(" ");
          return (
            <div key={entry.path} role="treeitem" aria-expanded={isFolder ? open : undefined} aria-selected={isCurrent}>
              {renaming === entry.path ? (
                <div className={`${classes} ft-editing`} style={{ paddingLeft: 8 + depth * 14 }}>
                  <span className={open ? "ft-chevron is-open" : "ft-chevron"} aria-hidden="true">
                    {isFolder && <Icon name="chevron" size={12} />}
                  </span>
                  {icon}
                  <InlineName initial={entry.name} file={!isFolder} label={`Nuovo nome per ${entry.name}`}
                              onCommit={(name) => rename(entry, name)} onCancel={() => setRenaming(null)} />
                </div>
              ) : (
                <button className={classes} style={{ paddingLeft: 8 + depth * 14 }}
                        title={entry.kind === "FILE" ? `${entry.path} · ${size(entry.size)}` : entry.path}
                        onClick={() => (isFolder ? toggle(entry) : openFile(entry))}
                        onContextMenu={(event) => onRowContextMenu(event, entry)}
                        onKeyDown={(event) => onRowKeyDown(event, entry)}>
                  <span className={open ? "ft-chevron is-open" : "ft-chevron"} aria-hidden="true">
                    {isFolder && <Icon name="chevron" size={12} />}
                  </span>
                  {icon}
                  <span className="ft-name">{entry.name}</span>
                </button>
              )}
              {isFolder && open && <div role="group">{renderFolder(entry.path, depth + 1)}</div>}
            </div>
          );
        })}
        {folder.listing.truncated && (
          <div className="ft-note muted" style={{ paddingLeft: 12 + depth * 14 }}>Solo le prime 5000 voci</div>
        )}
        {entries.length === 0 && creating?.parent !== path && (
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
        <button className="btn btn-small btn-icon" onClick={() => startCreate("", "FILE")} title="Nuovo file nella cartella del progetto"
                aria-label="Nuovo file" disabled={projectId === null || archived}><Icon name="file-plus" size={13} /></button>
        <button className="btn btn-small btn-icon" onClick={() => startCreate("", "DIRECTORY")} title="Nuova cartella nella cartella del progetto"
                aria-label="Nuova cartella" disabled={projectId === null || archived}><Icon name="folder-plus" size={13} /></button>
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
      <div className="ft-feedback" role="status" aria-live="polite">
        {notice && (
          <div className="notice ft-notice">
            <Icon name="check" size={13} /> <span>{notice.text}</span>
            {notice.detail && <span className="mono ft-notice-detail" title={notice.detail}>{notice.detail}</span>}
          </div>
        )}
      </div>
      {actionProblem !== null && (
        <div className="ft-problem"><ProblemNote problem={actionProblem} onDismiss={() => setActionProblem(null)} /></div>
      )}
      <div className="file-tree" role="tree" aria-label="Cartella del progetto"
           onContextMenu={(event) => { if (projectId !== null) { event.preventDefault(); openMenu(event.clientX, event.clientY, null); } }}>
        {projectId !== null && renderFolder("", 0)}
      </div>
      {menu && (
        <ContextMenu x={menu.x} y={menu.y} onClose={() => setMenu(null)} entries={menuEntries(menu)}
                     label={menu.target ? `Azioni su ${menu.target.name}` : `Azioni sulla cartella di ${current?.name ?? "progetto"}`} />
      )}
      {recycling && projectId !== null && (
        <ConfirmDeletion title="Sposta nel Cestino" subject={recycling.name} confirmLabel="Sposta nel Cestino" deletesTitle="Va nel Cestino"
                         intro={<>Stai per spostare <strong>«{recycling.path}»</strong> nel Cestino di Windows.
                           Si ripristina da lì.</>}
                         loadPreview={previewRecycle}
                         onClose={() => setRecycling(null)}
                         onConfirm={async () => {
                           try {
                             await api.recycleEntry(projectId, recycling.path);
                             say(`«${recycling.name}» è nel Cestino`);
                           } catch (error) {
                             if (!alreadyGone(error)) throw error;
                             say(`«${recycling.name}» non c'era più`);
                           }
                           recycled(recycling);
                         }} />
      )}
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
  const launcher = useOpenFileWith();
  const { clear } = launcher;

  useEffect(() => {
    let alive = true;
    setView(null);
    setProblem(null);
    clear();
    api.browseFile(file.projectId, file.path)
      .then((v) => { if (alive) setView(v); })
      .catch((e) => { if (alive) setProblem(e); });
    return () => { alive = false; };
  }, [api, file.projectId, file.path, clear]);

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
    const onKey = (event: globalThis.KeyboardEvent) => {
      // Escape that a field, a menu or a dialog used is theirs, not the viewer's.
      if (event.key !== "Escape" || event.defaultPrevented || document.querySelector("[aria-modal='true'], .ctx-menu")) return;
      onClose();
    };
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
        <OpenWithButton projectId={file.projectId} path={file.path} kind={view?.kind ?? null}
                        onOpen={(program) => launcher.open(program, file.projectId, file.path)} />
        <button className="btn btn-small" onClick={copyPath}><Icon name="copy" size={13} /> {copied ? "Copiato" : "Copia percorso"}</button>
        <button className="btn btn-small" onClick={onClose} aria-label="Chiudi il file"><Icon name="close" size={13} /> Chiudi</button>
      </div>
      {launcher.outcome && (
        <div className="notice file-outcome" role="status">
          <Icon name="check" size={13} /> <strong>{launcher.outcome.text}</strong>
          <span className="mono file-outcome-command" title={launcher.outcome.command}>{launcher.outcome.command}</span>
          <button className="btn btn-small" onClick={clear} aria-label="Chiudi l'avviso">Chiudi</button>
        </div>
      )}
      {launcher.problem !== null && <div className="file-outcome"><ProblemNote problem={launcher.problem} onDismiss={clear} /></div>}
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
