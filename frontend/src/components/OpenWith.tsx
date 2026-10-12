import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { ControlPlane } from "../api/client";
import type { Software } from "../api/types";
import { useApi } from "../context";
import { AppIcon } from "./AppIcon";
import { ContextMenu, type MenuEntry } from "./ContextMenu";
import { Icon } from "./icons";
import { openWith, shortName, type OpenWithChoice, type ViewKind } from "./fileOpeners";

/*
 * PHASE 33 (ADR-040): «Apri con» in the console -- the programs of the Software Hub
 * that can open a file, the suggested one first, and the launch.
 */

const TTL = 60_000;
const lists = new WeakMap<ControlPlane, { at: number; list: Promise<Software[]> }>();

/**
 * The Software Hub's programs, read once a minute at most: the control plane detects
 * them on every read, and the menus ask often. `enabled` false reads nothing yet.
 */
export function useFileOpeners(enabled = true): { software: Software[] | null; problem: unknown } {
  const api = useApi();
  const [software, setSoftware] = useState<Software[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  useEffect(() => {
    if (!enabled) return;
    let alive = true;
    let cached = lists.get(api);
    if (!cached || Date.now() - cached.at > TTL) {
      cached = { at: Date.now(), list: api.software() };
      lists.set(api, cached);
    }
    cached.list
      .then((list) => { if (alive) { setSoftware(list); setProblem(null); } })
      .catch((error) => { lists.delete(api); if (alive) setProblem(error); });
    return () => { alive = false; };
  }, [api, enabled]);
  return { software, problem };
}

/** The menu's lines: the suggested program, the ones that fit, the others, the ones that cannot now. */
export function openWithEntries(choice: OpenWithChoice | null, problem: unknown,
                                onPick: (software: Software) => void): MenuEntry[] {
  if (!choice) {
    return [{ key: "loading", label: problem ? "Il Software Hub non risponde" : "Leggo i programmi del Software Hub…",
      disabled: true }];
  }
  const item = (c: OpenWithChoice["suitable"][number], suggested = false): MenuEntry => ({
    key: c.software.key,
    icon: <AppIcon software={c.software} size={18} />,
    label: c.software.name,
    hint: c.unavailable ?? (c.reason || c.software.role),
    aside: suggested ? <span className="badge badge-accent">Consigliato</span> : undefined,
    disabled: c.unavailable !== null,
    title: c.unavailable ?? undefined,
    onSelect: () => onPick(c.software),
  });
  const entries: MenuEntry[] = [];
  if (choice.suggested) {
    entries.push({ heading: `Consigliato per ${choice.type.label}`, key: "h-suggested" }, item(choice.suggested, true));
  }
  if (choice.suitable.length > 0) {
    entries.push({ heading: "Adatti a questo file", key: "h-suitable" }, ...choice.suitable.map((c) => item(c)));
  }
  if (choice.others.length > 0) {
    entries.push({ heading: choice.suggested || choice.suitable.length ? "Altri programmi" : "Programmi del Software Hub",
      key: "h-others" }, ...choice.others.map((c) => item(c)));
  }
  if (choice.unavailable.length > 0) {
    entries.push({ heading: "Non disponibili su questo computer", key: "h-unavailable" },
      ...choice.unavailable.map((c) => item(c)));
  }
  if (entries.length === 0) {
    entries.push({ key: "none", label: "Nessun programma del Software Hub apre file", disabled: true });
  }
  return entries;
}

export interface Opened { ok: true; text: string; command: string }

/** Opens a file of a project with a program, and says what ran -- or why it did not. */
export function useOpenFileWith() {
  const api = useApi();
  const [outcome, setOutcome] = useState<Opened | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const open = useCallback((software: Software, projectId: number, path: string) => {
    setOutcome(null);
    setProblem(null);
    api.launch(software.key, projectId, path)
      .then((result) => setOutcome({ ok: true, text: `Aperto con ${shortName(software)}`, command: result.command.join(" ") }))
      .catch(setProblem);
  }, [api]);
  const clear = useCallback(() => { setOutcome(null); setProblem(null); }, []);
  return { open, outcome, problem, clear };
}

/**
 * The viewer's button: «Apri con <the suggested program>», and next to it the menu of
 * every program. The suggestion follows the file -- a new file, a new suggestion.
 */
export function OpenWithButton({ projectId, path, kind, onOpen }: {
  projectId: number; path: string; kind: ViewKind | null;
  onOpen: (software: Software) => void;
}) {
  const { software, problem } = useFileOpeners();
  const choice = useMemo(() => (software ? openWith(software, path, kind) : null), [software, path, kind]);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const caret = useRef<HTMLButtonElement>(null);
  const suggested = choice?.suggested ?? null;

  useEffect(() => { setMenu(null); }, [projectId, path]);

  const openMenu = () => {
    const box = caret.current?.getBoundingClientRect();
    setMenu(box ? { x: box.left, y: box.bottom + 4 } : { x: 0, y: 0 });
  };

  let label: ReactNode = "Apri con…";
  if (suggested) label = <>Apri con {shortName(suggested.software)}</>;
  return (
    <>
      <div className="split-button" role="group" aria-label="Apri con">
        <button type="button" className="btn btn-small" onClick={() => (suggested ? onOpen(suggested.software) : openMenu())}
                title={suggested ? `${suggested.reason} — ${choice!.type.label}` : "Scegli il programma del Software Hub"}>
          <span className="split-icon" aria-hidden="true">{suggested ? <AppIcon software={suggested.software} size={16} /> : <Icon name="external" size={13} />}</span> {label}
        </button>
        <button ref={caret} type="button" className="btn btn-small split-caret" aria-haspopup="menu"
                aria-expanded={menu !== null} aria-label="Scegli con quale programma aprire" onClick={openMenu}>
          <Icon name="chevron" size={12} className="split-caret-icon" />
        </button>
      </div>
      {menu && (
        <ContextMenu x={menu.x} y={menu.y} label="Apri con" onClose={() => setMenu(null)}
                     entries={openWithEntries(choice, problem, onOpen)} />
      )}
    </>
  );
}
