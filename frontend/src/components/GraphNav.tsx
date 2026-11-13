// The navigation cluster that sits on a graph canvas, the same in the 3D ecosystem
// and in the Second Brain: zoom, fit, and whatever else the view adds. Buttons
// only -- each view decides what a press does; the keyboard does the same things.
import type { ReactNode } from "react";

export type NavGlyph = "plus" | "minus" | "fit" | "home" | "rotate" | "front" | "top" | "side" | "layout" | "back" | "keys";

const GLYPH: Record<NavGlyph, ReactNode> = {
  plus: <path d="M12 5v14M5 12h14" />,
  minus: <path d="M5 12h14" />,
  fit: <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />,
  home: <path d="M4 11.5 12 5l8 6.5M6.5 10v9h11v-9" />,
  rotate: <path d="M20 12a8 8 0 1 1-2.34-5.66M20 4v5h-5" />,
  front: <><rect x="6" y="6" width="12" height="12" rx="1.5" /><path d="M12 9v6M9 12h6" /></>,
  top: <><ellipse cx="12" cy="12" rx="8" ry="4" /><path d="M12 4v4" /></>,
  side: <><path d="M5 18 12 6l7 12Z" /></>,
  layout: <><circle cx="6" cy="7" r="2" /><circle cx="18" cy="7" r="2" /><circle cx="12" cy="17" r="2" /><path d="M8 7h8M7 9l4 6.3M17 9l-4 6.3" /></>,
  back: <path d="M15 5 8 12l7 7" />,
  keys: <><rect x="3" y="7" width="18" height="10" rx="2" /><path d="M7 11h.01M11 11h.01M15 11h.01M8 14.5h8" /></>,
};

export interface NavButton {
  glyph: NavGlyph;
  /** The accessible name and the tooltip, with its key in brackets when there is one. */
  label: string;
  onClick: () => void;
  pressed?: boolean;
  disabled?: boolean;
}

/** Groups of buttons, stacked; a hairline between groups. */
export function GraphNav({ groups, label = "Navigazione" }: { groups: NavButton[][]; label?: string }) {
  return (
    <div className="graph-nav" role="toolbar" aria-label={label} aria-orientation="vertical">
      {groups.filter((g) => g.length > 0).map((group, i) => (
        <div key={i} className="graph-nav-group">
          {group.map((b) => (
            <button key={b.label} type="button" className="graph-nav-btn" title={b.label} aria-label={b.label}
                    aria-pressed={b.pressed} disabled={b.disabled} onClick={b.onClick}>
              <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
                   strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{GLYPH[b.glyph]}</svg>
            </button>
          ))}
        </div>
      ))}
    </div>
  );
}

/** A trail of where the operator has been: each step goes back there. */
export function GraphTrail({ steps, onBack, canBack }: {
  steps: { key: string; label: string; color?: string; onClick: () => void; current?: boolean }[];
  onBack?: () => void;
  canBack?: boolean;
}) {
  return (
    <nav className="graph-trail" aria-label="Percorso">
      {onBack && (
        <button type="button" className="graph-trail-back" onClick={onBack} disabled={!canBack} title="Indietro (Backspace)" aria-label="Indietro">
          <svg width={14} height={14} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2}
               strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{GLYPH.back}</svg>
        </button>
      )}
      <ol>
        {steps.map((s) => (
          <li key={s.key}>
            <button type="button" onClick={s.onClick} aria-current={s.current ? "location" : undefined}>
              {s.color && <span className="graph-trail-dot" style={{ background: s.color }} aria-hidden="true" />}
              <span className="graph-trail-label">{s.label}</span>
            </button>
          </li>
        ))}
      </ol>
    </nav>
  );
}

/** The keys a graph answers to, shown on request. */
export function GraphKeys({ keys, onClose }: { keys: [string, string][]; onClose: () => void }) {
  return (
    <div className="graph-keys" role="dialog" aria-label="Scorciatoie da tastiera">
      <div className="graph-keys-head"><strong>Scorciatoie</strong>
        <button type="button" className="graph-nav-btn" aria-label="Chiudi le scorciatoie" onClick={onClose}>
          <svg width={14} height={14} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" aria-hidden="true">
            <path d="M6 6l12 12M18 6 6 18" /></svg>
        </button>
      </div>
      <dl>
        {keys.map(([k, what]) => <div key={k}><dt><kbd>{k}</kbd></dt><dd>{what}</dd></div>)}
      </dl>
    </div>
  );
}
