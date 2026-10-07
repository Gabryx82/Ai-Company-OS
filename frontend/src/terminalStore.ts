// The shells this browser tab shows (ADR-029 §5), kept where the session token
// is: in sessionStorage, which survives a reload and not a closed tab. After a
// reload the console attaches to them again; a shell another tab shows, or one
// left when a tab was closed, is offered in the terminal's "in background" list.
const KEY = "aicos.terminals";

export interface StoredTerminal {
  session: string;
  shell: string;
  name: string;
}

export function storedTerminals(): StoredTerminal[] {
  try {
    const raw = sessionStorage.getItem(KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(parsed)
      ? parsed.filter((t): t is StoredTerminal => typeof t?.session === "string" && typeof t?.shell === "string")
      : [];
  } catch {
    return [];
  }
}

export function storeTerminals(terminals: StoredTerminal[]): void {
  try {
    if (terminals.length) sessionStorage.setItem(KEY, JSON.stringify(terminals));
    else sessionStorage.removeItem(KEY);
  } catch {
    // Storage unavailable: a reload then loses the tabs, and the shells are in the "in background" list.
  }
}

export function clearStoredTerminals(): void {
  storeTerminals([]);
}
