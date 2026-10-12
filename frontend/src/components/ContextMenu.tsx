import { useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { Icon } from "./icons";

/*
 * PHASE 33 (ADR-040): a menu that opens where it was asked for -- the pointer of a right
 * click, or under a button -- and behaves like the menus of the operating system: the
 * first item has the focus, the arrows move it, Enter runs an item, Escape or a click
 * elsewhere closes it, and it never leaves the window.
 *
 * It is drawn in <body>, not where it is declared: the file panel is sticky, and a sticky
 * element is a stacking context of its own -- a menu inside it, whatever its z-index,
 * went under the page next to it (seen by the operator, 2026-10-13).
 */

export interface MenuItem {
  key: string;
  label: ReactNode;
  /** An icon of the console's set by name, or anything to show in its place (a program's own icon). */
  icon?: string | ReactNode;
  /** A second line, smaller: why it is suggested, why it is disabled. */
  hint?: ReactNode;
  /** Shown on the right: a shortcut ("F2"), an arrow for a submenu. */
  aside?: ReactNode;
  disabled?: boolean;
  /** A title for a disabled item: why. */
  title?: string;
  danger?: boolean;
  /** Keeps the menu open after the item runs (it changes the menu's content). */
  keepOpen?: boolean;
  onSelect?: () => void;
}

export type MenuEntry = MenuItem | { separator: string } | { heading: ReactNode; key: string };

export function ContextMenu({ x, y, label, entries, onClose, footer }: {
  x: number; y: number; label: string; entries: MenuEntry[]; onClose: () => void; footer?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: x, top: y });
  // What the menu shows: a new array on every render of the caller, the same items until it changes.
  const signature = entries.map((entry) => ("key" in entry ? entry.key : "-")).join("|");

  // Kept inside the window: flipped left or up when it would overflow.
  useLayoutEffect(() => {
    const menu = ref.current;
    if (!menu) return;
    const { width, height } = menu.getBoundingClientRect();
    const margin = 8;
    const left = x + width + margin > window.innerWidth ? Math.max(margin, window.innerWidth - width - margin) : x;
    const top = y + height + margin > window.innerHeight ? Math.max(margin, y - height) : y;
    setPosition({ left, top });
  }, [x, y, signature]);

  useEffect(() => {
    const first = ref.current?.querySelector<HTMLButtonElement>("[role=menuitem]:not(:disabled)");
    first?.focus();
  }, [signature]);

  useEffect(() => {
    const away = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) onClose();
    };
    const close = () => onClose();
    window.addEventListener("mousedown", away, true);
    window.addEventListener("resize", close);
    window.addEventListener("blur", close);
    return () => {
      window.removeEventListener("mousedown", away, true);
      window.removeEventListener("resize", close);
      window.removeEventListener("blur", close);
    };
  }, [onClose]);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>("[role=menuitem]:not(:disabled)") ?? []);
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const next = event.key === "ArrowDown" ? (at + 1) % items.length : (at - 1 + items.length) % items.length;
      items[next]?.focus();
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      items[event.key === "Home" ? 0 : items.length - 1]?.focus();
    } else if (event.key === "Tab") {
      event.preventDefault();
      onClose();
    }
  };

  return createPortal(
    <div ref={ref} className="ctx-menu" role="menu" aria-label={label} style={{ left: position.left, top: position.top }}
         onKeyDown={onKeyDown} onContextMenu={(event) => event.preventDefault()}>
      {entries.map((entry, index) => {
        if ("separator" in entry) return <div key={`sep-${entry.separator}-${index}`} className="ctx-separator" role="separator" />;
        if ("heading" in entry) return <div key={entry.key} className="ctx-heading">{entry.heading}</div>;
        return (
          <button key={entry.key} type="button" role="menuitem" disabled={entry.disabled} title={entry.title}
                  className={entry.danger ? "ctx-item is-danger" : "ctx-item"}
                  onClick={() => { entry.onSelect?.(); if (!entry.keepOpen) onClose(); }}>
            <span className="ctx-icon" aria-hidden="true">
              {typeof entry.icon === "string" ? <Icon name={entry.icon} size={14} /> : entry.icon}</span>
            <span className="ctx-text">
              <span className="ctx-label">{entry.label}</span>
              {entry.hint && <small className="ctx-hint">{entry.hint}</small>}
            </span>
            {entry.aside && <span className="ctx-aside">{entry.aside}</span>}
          </button>
        );
      })}
      {footer}
    </div>,
    document.body,
  );
}
