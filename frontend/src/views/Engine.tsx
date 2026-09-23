import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { ModelList } from "../api/types";
import { ProblemNote } from "../components/ui";

export function Engine() {
  const api = useApi();
  const [list, setList] = useState<ModelList | null>(null);
  const [problem, setProblem] = useState<unknown>(null);

  useEffect(() => { api.engineModels().then(setList).catch(setProblem); }, [api]);

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Models</h1>
          <p>What the AI Engine can run right now. Billed models run only when their key is configured on the engine.</p>
        </div>
      </div>
      <ProblemNote problem={problem} />
      {list && (
        <div className="card">
          <table>
            <thead><tr><th>Model</th><th>Provider</th><th>Availability</th><th>Cost</th></tr></thead>
            <tbody>
              {list.models.map((model) => (
                <tr key={model.id}>
                  <td className="mono">{model.id}{model.id === list.defaultModel && <> <span className="badge badge-accent">default</span></>}</td>
                  <td>{model.provider}</td>
                  <td>
                    <span className={model.available ? "badge badge-ok" : "badge"}>{model.available ? "available" : "unavailable"}</span>
                    {model.detail && <div className="muted">{model.detail}</div>}
                  </td>
                  <td>{model.billed ? <span className="badge badge-warn">billed per token</span> : <span className="muted">free</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
