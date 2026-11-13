// The console's two themes: dark by default, light on request. The choice is a
// per-viewer convenience kept in this browser; storage can be unavailable, and
// the console then simply starts dark.

export type Theme = "dark" | "light";

const KEY = "aicos.theme";

export function storedTheme(): Theme {
  try {
    return localStorage.getItem(KEY) === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
}

export function saveTheme(theme: Theme) {
  applyTheme(theme);
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    // The theme still changes for this page.
  }
}
