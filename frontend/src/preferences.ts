// Per-viewer conveniences, kept in localStorage: never state the control plane
// needs. Every access is guarded -- storage can be unavailable.

const NAME_KEY = "aicos.displayName";

export function displayName(): string {
  try {
    return localStorage.getItem(NAME_KEY) || "Operatore";
  } catch {
    return "Operatore";
  }
}

export function setDisplayName(name: string): void {
  try {
    localStorage.setItem(NAME_KEY, name.trim());
  } catch {
    // not remembered; the default stays
  }
}
