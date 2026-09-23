import type { Session } from "./api/client";

// The operator's token lives in sessionStorage: it survives a reload, not a
// closed tab, and it is never written to a cookie -- the browser must not
// attach it by itself (ADR-013 §3), which is what keeps CSRF out of the picture.
const KEY = "aicos.session";

export const DEFAULT_BASE_URL = "http://localhost:8080";

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Session;
    return parsed.token && parsed.baseUrl ? parsed : null;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(session));
  } catch {
    // Storage can be unavailable (private mode, blocked); the session then
    // lasts until the page is reloaded, which is still correct.
  }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // nothing to clear
  }
}
