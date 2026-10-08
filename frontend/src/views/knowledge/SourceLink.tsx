import { useState } from "react";
import { useApi } from "../../context";
import type { HubResult, HubSkill } from "../../api/types";
import { ProblemNote } from "../../components/ui";

/**
 * PHASE 32 (TD-80): where a library skill comes from. One written by hand, or
 * imported from an application, can be tied to its package online -- found by
 * its name across the marketplaces -- and from then on its updates come from there.
 */
export function SourceLink({ skill, onChanged }: { skill: HubSkill; onChanged: (message: string) => void }) {
  const api = useApi();
  const [results, setResults] = useState<HubResult[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [searching, setSearching] = useState(false);
  const source = skill.provenance;
  const online = !!source && source.provider !== "app";

  async function search() {
    setProblem(null);
    setSearching(true);
    try {
      setResults((await api.hubSearch(skill.name, [], "SKILL")).results.slice(0, 8));
    } catch (error) {
      setProblem(error);
    } finally {
      setSearching(false);
    }
  }

  async function link(result: HubResult) {
    setBusy(true);
    setProblem(null);
    try {
      onChanged((await api.hubLinkSource(skill.libraryKey!, result.provider, result.ref)).message);
      setResults(null);
    } catch (error) {
      setProblem(error);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack" style={{ gap: 6, fontSize: 12.5 }}>
      <div className="row">
        <span className="muted">Fonte</span>
        <span style={{ flex: 1 }}>{source ? `${source.providerName} · ${source.reference}` : "nessuna: scritta a mano nella libreria"}</span>
        <button className="btn btn-small" disabled={busy || searching} onClick={search}>
          {online ? "Cambia fonte" : "Collega a una fonte online"}</button>
      </div>
      {searching && <div className="muted">Cerco «{skill.name}» nei marketplace…</div>}
      {!searching && results && results.length === 0 && <div className="muted">Nessun pacchetto con questo nome nei marketplace.</div>}
      {results && results.length > 0 && (
        <ul className="deletion-lines" aria-label="Fonti possibili">
          {results.map((r) => (
            <li key={`${r.provider}:${r.ref}`} className="row">
              <span style={{ flex: 1 }}><strong>{r.name}</strong> <span className="muted">{r.provider} · {r.ref}</span></span>
              <button className="btn btn-small" disabled={busy} onClick={() => link(r)}
                      aria-label={`Collega a ${r.provider} ${r.ref}`}>Collega</button>
            </li>
          ))}
        </ul>
      )}
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
    </div>
  );
}
