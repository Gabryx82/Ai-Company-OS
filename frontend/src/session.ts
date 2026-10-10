import type { Session } from "./api/client";

// The browser session (ADR-024; PHASE 32, TD-53). The session token is an HttpOnly
// cookie the control plane sets and the page never sees: no script -- an injected
// one included -- can read it or carry it away. What this tab keeps in
// sessionStorage is where the control plane is, who signed in, and the session's
// anti-forgery token: none of them is a credential on its own. A 401 brings the
// login back; another tab of the same browser finds the cookie and signs in by itself.
const KEY = "aicos.session";

/**
 * The control plane on the host this page was opened from: the browser sends the
 * session cookie back only to the same host name (SameSite=Strict), so
 * localhost goes with localhost and 127.0.0.1 with 127.0.0.1.
 */
export function defaultBaseUrl(): string {
  const host = typeof window !== "undefined" && window.location?.hostname ? window.location.hostname : "localhost";
  return `http://${host}:8081`;
}

export const DEFAULT_BASE_URL = defaultBaseUrl();

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Session;
    if (parsed.token) {
      // Kept by a console from before TD-53: a session token in storage is exactly what
      // must not be there. Dropped; the cookie or a new sign-in takes its place.
      sessionStorage.removeItem(KEY);
      return null;
    }
    return parsed.baseUrl && parsed.csrf ? { baseUrl: parsed.baseUrl, csrf: parsed.csrf, user: parsed.user } : null;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  try {
    const { baseUrl, csrf, user } = session;
    sessionStorage.setItem(KEY, JSON.stringify({ baseUrl, csrf, user }));
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
