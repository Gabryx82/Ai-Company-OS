import type { ReactNode } from "react";
import type { CodeMethod, CodeStructure, StructureSection } from "../../api/types";

type ScalarKey = "packageName" | "className" | "extendsType" | "signature" | "output" | "visibility" | "httpMethod" | "route"
  | "controller" | "requestDto" | "responseDto" | "service" | "authorization" | "table";
type ListKey = "implementsTypes" | "annotations" | "dependencies" | "exceptions" | "relatedTests" | "inputs" | "calls"
  | "calledBy" | "statusCodes" | "exports" | "imports" | "props" | "hooks" | "columns";

interface Row { key: ScalarKey | ListKey; label: string; list?: boolean }

/** The fields of each section (directive §7), in the order a reader asks for them. */
const SECTIONS: Record<StructureSection, Row[]> = {
  CLASS: [
    { key: "className", label: "Class name" }, { key: "packageName", label: "Package / namespace" },
    { key: "extendsType", label: "Extends" }, { key: "implementsTypes", label: "Implements", list: true },
    { key: "dependencies", label: "Dependencies", list: true }, { key: "annotations", label: "Annotations", list: true },
    { key: "exceptions", label: "Exceptions", list: true }, { key: "relatedTests", label: "Related tests", list: true },
  ],
  FUNCTION: [
    { key: "signature", label: "Signature" }, { key: "inputs", label: "Inputs", list: true }, { key: "output", label: "Output" },
    { key: "visibility", label: "Visibility" }, { key: "annotations", label: "Annotations", list: true },
    { key: "calls", label: "Calls", list: true }, { key: "calledBy", label: "Called by", list: true },
    { key: "exceptions", label: "Exceptions", list: true },
  ],
  ENDPOINT: [
    { key: "httpMethod", label: "Method" }, { key: "route", label: "Route" }, { key: "controller", label: "Controller" },
    { key: "requestDto", label: "Request DTO" }, { key: "responseDto", label: "Response DTO" }, { key: "service", label: "Service" },
    { key: "authorization", label: "Authorization" }, { key: "statusCodes", label: "Status codes", list: true },
    { key: "relatedTests", label: "Related tests", list: true },
  ],
  FRONTEND: [
    { key: "exports", label: "Exports", list: true }, { key: "props", label: "Props", list: true },
    { key: "hooks", label: "Hooks", list: true }, { key: "imports", label: "Imports", list: true },
    { key: "relatedTests", label: "Related tests", list: true },
  ],
  DATABASE: [{ key: "table", label: "Table" }, { key: "columns", label: "Columns", list: true }],
  NONE: [],
};

/**
 * Code Structure (directive §7, §10): the parts of code a block is made of, as
 * fields and not as prose. Read-only when the parser wrote them; controls when
 * the block is the operator's and the editor is on.
 */
export function StructurePanel({ structure, section, editable, onChange }: {
  structure: CodeStructure; section: StructureSection; editable: boolean; onChange: (s: CodeStructure) => void;
}) {
  const rows = SECTIONS[section].filter((r) => editable || hasValue(structure, r));
  const set = (key: ScalarKey | ListKey, value: string, list?: boolean) =>
    onChange({ ...structure, [key]: list ? value.split(",").map((v) => v.trim()).filter(Boolean) : value || null });
  const showFields = section === "CLASS" && (editable || structure.fields.length > 0);
  const showMethods = section === "CLASS" && (editable || structure.methods.length > 0);
  if (rows.length === 0 && !showFields && !showMethods) {
    return <div className="muted" style={{ fontSize: 12.5 }}>Nessun campo strutturato per questo tipo di blocco.</div>;
  }
  return (
    <div className="stack" style={{ gap: 8 }}>
      <dl className="bp-kv">
        {rows.map((r) => (
          <Pair key={r.key} label={r.label}>
            {editable
              ? <input aria-label={r.label} value={r.list ? (structure[r.key as ListKey] as string[]).join(", ") : (structure[r.key as ScalarKey] as string | null) ?? ""}
                       placeholder={r.list ? "valori separati da virgola" : ""} onChange={(e) => set(r.key, e.target.value, r.list)} />
              : r.list ? <Chips values={structure[r.key as ListKey] as string[]} />
                : <code>{structure[r.key as ScalarKey] as string}</code>}
          </Pair>
        ))}
      </dl>
      {showFields && (
        <div>
          <div className="bp-subtitle">Fields</div>
          {structure.fields.length === 0 && <div className="muted" style={{ fontSize: 12 }}>—</div>}
          {structure.fields.map((f) => (
            <div key={f.name} className="bp-member mono">
              <span className="muted">{f.visibility}</span> {f.type} <b>{f.name}</b>
              {f.annotations.length > 0 && <span className="muted"> @{f.annotations.join(" @")}</span>}
            </div>
          ))}
        </div>
      )}
      {showMethods && <Methods methods={structure.methods} editable={editable}
                               onChange={(methods) => onChange({ ...structure, methods })} />}
    </div>
  );
}

function Methods({ methods, editable, onChange }: { methods: CodeMethod[]; editable: boolean; onChange: (m: CodeMethod[]) => void }) {
  const publicOnes = methods.filter((m) => m.visibility === "public" || m.visibility === null);
  const others = methods.filter((m) => !publicOnes.includes(m));
  const update = (i: number, patch: Partial<CodeMethod>) => onChange(methods.map((m, j) => (j === i ? { ...m, ...patch } : m)));
  if (editable) {
    return (
      <div className="stack" style={{ gap: 6 }}>
        <div className="bp-subtitle">Methods</div>
        {methods.map((m, i) => (
          <div key={i} className="bp-method-edit">
            <input aria-label="Nome del metodo" value={m.name} placeholder="nome" onChange={(e) => update(i, { name: e.target.value })} />
            <input aria-label="Parametri" value={m.parameters.join(", ")} placeholder="Long id, String title"
                   onChange={(e) => update(i, { parameters: e.target.value.split(",").map((v) => v.trim()).filter(Boolean) })} />
            <input aria-label="Tipo restituito" value={m.returnType ?? ""} placeholder="void"
                   onChange={(e) => update(i, { returnType: e.target.value || null })} />
            <select aria-label="Visibilità" value={m.visibility ?? "public"} onChange={(e) => update(i, { visibility: e.target.value })}>
              {["public", "protected", "package", "private"].map((v) => <option key={v}>{v}</option>)}
            </select>
            <button type="button" className="btn btn-small btn-danger" aria-label={`Rimuovi ${m.name}`}
                    onClick={() => onChange(methods.filter((_, j) => j !== i))}>×</button>
          </div>
        ))}
        <button type="button" className="btn btn-small" onClick={() => onChange([...methods, emptyMethod()])}>+ Metodo</button>
      </div>
    );
  }
  return (
    <div>
      <div className="bp-subtitle">Public methods</div>
      {publicOnes.map((m) => <MethodLine key={`${m.name}${m.signature}`} method={m} />)}
      {publicOnes.length === 0 && <div className="muted" style={{ fontSize: 12 }}>—</div>}
      {others.length > 0 && (
        <details>
          <summary className="bp-subtitle" style={{ cursor: "pointer" }}>Private / protected methods ({others.length})</summary>
          {others.map((m) => <MethodLine key={`${m.name}${m.signature}`} method={m} />)}
        </details>
      )}
    </div>
  );
}

function MethodLine({ method }: { method: CodeMethod }) {
  return (
    <details className="bp-method">
      <summary className="mono">{method.signature ?? `${method.name}()`}</summary>
      <div className="stack" style={{ gap: 4, padding: "4px 0 6px 12px" }}>
        {method.annotations.length > 0 && <div className="muted" style={{ fontSize: 12 }}>@{method.annotations.join(" @")}</div>}
        {method.calls.length > 0 && <div style={{ fontSize: 12 }}><span className="muted">calls </span><Chips values={method.calls} /></div>}
        {(method.invokes ?? []).length > 0 && (
          <div style={{ fontSize: 12 }} aria-label={`Metodi invocati da ${method.name}`}><span className="muted">invokes </span>
            {(method.invokes ?? []).map((m) => <span key={m} className="chip mono" title={m}>{shortMethod(m)}</span>)}</div>
        )}
        {method.exceptions.length > 0 && <div style={{ fontSize: 12 }}><span className="muted">throws </span><Chips values={method.exceptions} /></div>}
        {method.pseudocode && <pre className="bp-pseudo">{method.pseudocode}</pre>}
      </div>
    </details>
  );
}

/** PHASE 32 (TD-58): {@code com.acme.Store#save} as {@code Store.save()}; the whole name stays in the title. */
export function shortMethod(method: string): string {
  const [type, name] = method.split("#");
  return `${type.slice(type.lastIndexOf(".") + 1)}.${name ?? ""}()`;
}

function Pair({ label, children }: { label: string; children: ReactNode }) {
  return <><dt>{label}</dt><dd>{children}</dd></>;
}

function Chips({ values }: { values: string[] }) {
  if (values.length === 0) return <span className="muted">—</span>;
  return <>{values.map((v) => <span key={v} className="chip mono">{v}</span>)}</>;
}

function hasValue(s: CodeStructure, r: Row): boolean {
  const v = s[r.key as keyof CodeStructure];
  return Array.isArray(v) ? v.length > 0 : v !== null && v !== undefined && v !== "";
}

function emptyMethod(): CodeMethod {
  return { name: "", signature: null, visibility: "public", returnType: "void", parameters: [], annotations: [], exceptions: [],
    calls: [], callTargets: [], isStatic: false, startLine: null, endLine: null, pseudocode: null, invokes: [] };
}
