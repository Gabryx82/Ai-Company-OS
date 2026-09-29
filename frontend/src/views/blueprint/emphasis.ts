// What the canvas is pointing at: the selected block and its relations, the
// neighbours being centred, a relation flashed after a chip was clicked. A tiny
// external store, read with useSyncExternalStore and a per-element selector, so a
// change re-renders only the blocks and edges whose emphasis changes -- not the
// hundreds of others (directive §18, performance).
import { createContext, useContext, useSyncExternalStore } from "react";

export type Emphasis = "focus" | "dim" | "none";

interface State {
  nodes: Set<number>;
  edges: Set<number>;
  /** When something is focused, everything else is dimmed. */
  active: boolean;
  hoverEdge: number | null;
}

export class EmphasisStore {
  private state: State = { nodes: new Set(), edges: new Set(), active: false, hoverEdge: null };
  private readonly listeners = new Set<() => void>();

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  get(): State {
    return this.state;
  }

  focus(nodes: number[], edges: number[]) {
    this.state = { ...this.state, nodes: new Set(nodes), edges: new Set(edges), active: nodes.length + edges.length > 0 };
    this.emit();
  }

  clear() {
    this.focus([], []);
  }

  hover(edge: number | null) {
    if (this.state.hoverEdge === edge) return;
    this.state = { ...this.state, hoverEdge: edge };
    this.emit();
  }

  private emit() {
    this.listeners.forEach((l) => l());
  }
}

export const EmphasisContext = createContext<EmphasisStore | null>(null);

const idle = () => () => undefined;

export function useNodeEmphasis(id: number): Emphasis {
  const store = useContext(EmphasisContext);
  return useSyncExternalStore(store?.subscribe ?? idle, () => {
    const s = store?.get();
    if (!s || !s.active) return "none";
    return s.nodes.has(id) ? "focus" : "dim";
  });
}

export function useEdgeEmphasis(id: number): Emphasis | "hover" {
  const store = useContext(EmphasisContext);
  return useSyncExternalStore(store?.subscribe ?? idle, () => {
    const s = store?.get();
    if (!s) return "none";
    if (s.hoverEdge === id) return "hover";
    if (!s.active) return "none";
    return s.edges.has(id) ? "focus" : "dim";
  });
}

/** What a block can ask the view to do: follow a relation chip. */
export interface BlockActions {
  followChip: (targetId: number, edgeId: number) => void;
}

export const BlockActionsContext = createContext<BlockActions>({ followChip: () => undefined });
