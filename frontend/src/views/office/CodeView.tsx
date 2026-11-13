import { useEffect, useMemo, useRef } from "react";

/**
 * A file's text with line numbers, its comments set apart (so what the agent explains
 * reads first), and the lines that changed since the last read lit up.
 */
export function commentLines(text: string): boolean[] {
  const lines = text.split("\n");
  const marks: boolean[] = [];
  let block: "c" | "html" | "py" | null = null;
  for (const raw of lines) {
    const line = raw.trim();
    if (block) {
      marks.push(true);
      if ((block === "c" && line.includes("*/")) || (block === "html" && line.includes("-->"))
          || (block === "py" && (line.endsWith('"""') || line.endsWith("'''")))) block = null;
      continue;
    }
    if (line.startsWith("/*") || line.startsWith("/**")) {
      marks.push(true);
      if (!line.includes("*/")) block = "c";
      continue;
    }
    if (line.startsWith("<!--")) {
      marks.push(true);
      if (!line.includes("-->")) block = "html";
      continue;
    }
    if ((line.startsWith('"""') || line.startsWith("'''")) && line.length >= 3) {
      marks.push(true);
      const quote = line.slice(0, 3);
      if (line.length === 3 || !line.slice(3).includes(quote)) block = "py";
      continue;
    }
    marks.push(line.startsWith("//") || line.startsWith("#") || line.startsWith("--") || line.startsWith("* ")
      || line === "*" || line.startsWith("REM ") || line.startsWith(";"));
  }
  return marks;
}

/** The lines of `next` that were not in `previous` (by position and text), for highlighting. */
export function changedLines(previous: string | null, next: string): Set<number> {
  if (previous === null || previous === next) return new Set();
  const before = previous.split("\n");
  const after = next.split("\n");
  const seen = new Map<string, number>();
  before.forEach((l) => seen.set(l, (seen.get(l) ?? 0) + 1));
  const changed = new Set<number>();
  after.forEach((l, i) => {
    const n = seen.get(l) ?? 0;
    if (n > 0) seen.set(l, n - 1);
    else if (l.trim()) changed.add(i);
  });
  return changed;
}

export function CodeView({ text, changed, label }: { text: string; changed?: Set<number>; label: string }) {
  const lines = useMemo(() => text.split("\n"), [text]);
  const comments = useMemo(() => commentLines(text), [text]);
  const box = useRef<HTMLDivElement>(null);
  const first = changed && changed.size > 0 ? Math.min(...changed) : null;
  useEffect(() => {
    if (first === null || !box.current) return;
    const row = box.current.querySelector<HTMLElement>(`[data-line="${first}"]`);
    if (row) box.current.scrollTo?.({ top: Math.max(0, row.offsetTop - box.current.clientHeight / 3), behavior: "smooth" });
  }, [first, text]);
  return (
    <div className="code-view" ref={box} role="region" aria-label={label} tabIndex={0}>
      <ol>
        {lines.map((line, i) => (
          <li key={i} data-line={i} className={`${comments[i] ? "is-comment" : ""}${changed?.has(i) ? " is-new" : ""}`}>
            <code>{line || " "}</code>
          </li>
        ))}
      </ol>
    </div>
  );
}
