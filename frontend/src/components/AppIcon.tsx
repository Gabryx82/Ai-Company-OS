import { useEffect, useState } from "react";
import type { Software } from "../api/types";
import { useApi } from "../context";

// One hue per category, for the monogram tile a program shows when it has no
// icon of its own (not installed, or a web site whose favicon did not load).
const HUES: Record<string, number> = {
  AGENTIC_IDE: 265, IDE: 215, EDITOR: 190, TERMINAL: 150, AI_CLOUD: 20, AI_LOCAL: 170, DATABASE: 200,
  API: 30, INFRASTRUCTURE: 230, DIAGRAM: 40, DESIGN: 320, THREE_D: 290, VCS: 0, HOSTING: 250, PRODUCTIVITY: 120,
};

const iconCache = new Map<string, Promise<string | null>>();

/**
 * The real icon of a program: extracted from the installed executable by the
 * control plane, or the site's own favicon for web entries; a monogram tile
 * otherwise. The repository ships nobody's logo.
 */
export function AppIcon({ software, size = 40 }: { software: Software; size?: number }) {
  const api = useApi();
  const [src, setSrc] = useState<string | null>(software.iconUrl ?? null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (software.iconUrl || software.launchKind === "WEB") return;
    if (software.availability !== "INSTALLED" && software.availability !== "RUNNING"
        && software.availability !== "STOPPED") return;
    let cancelled = false;
    if (!iconCache.has(software.key)) iconCache.set(software.key, api.softwareIcon(software.key));
    iconCache.get(software.key)!.then((url) => { if (!cancelled && url) setSrc(url); });
    return () => { cancelled = true; };
  }, [api, software.key, software.iconUrl, software.launchKind, software.availability]);

  const hue = HUES[software.category] ?? 220;
  const initials = software.name.replace(/[^A-Za-z0-9 +]/g, "").split(/\s+/).filter(Boolean)
    .slice(0, 2).map((word) => word[0]).join("").toUpperCase();

  return (
    <span className="app-icon" style={{ width: size, height: size,
      background: src && !failed ? "var(--surface-3)" : `linear-gradient(135deg, hsl(${hue} 70% 45%), hsl(${hue + 30} 70% 32%))` }}>
      {src && !failed
        ? <img src={src} alt="" width={size * 0.66} height={size * 0.66} onError={() => setFailed(true)} />
        : <span style={{ fontSize: size * 0.36 }}>{initials || "?"}</span>}
    </span>
  );
}
