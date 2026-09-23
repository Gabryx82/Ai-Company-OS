import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../context";
import { ApiProblem, type Versioned } from "../api/client";
import type { Agent, ModelInfo } from "../api/types";
import { Field, Modal, ProblemNote } from "../components/ui";

export function Agents() {
  const api = useApi();
  const [agents, setAgents] = useState<Agent[] | null>(null);
  const [models, setModels] = useState<ModelInfo[]>([]);
  const [problem, setProblem] = useState<unknown>(null);
  const [editing, setEditing] = useState<Versioned<Agent> | "new" | null>(null);

  const reload = useCallback(() => { api.agents().then(setAgents).catch(setProblem); }, [api]);
  useEffect(reload, [reload]);
  useEffect(() => { api.engineModels().then((l) => setModels(l.models)).catch(() => setModels([])); }, [api]);

  // The listing carries no ETag (TD-33): an action on a row reads that one agent
  // first, and acts with the tag it was just given.
  async function toggle(agent: Agent) {
    setProblem(null);
    try {
      const current = await api.agent(agent.id);
      await api.setAgentActive(agent.id, current.etag, !current.body.active);
      reload();
    } catch (error) {
      setProblem(error);
    }
  }

  async function edit(agent: Agent) {
    try {
      setEditing(await api.agent(agent.id));
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Agents</h1><p>The registry. An agent's model is the one its runs use unless a run asks for another.</p></div>
        <button className="btn btn-primary" onClick={() => setEditing("new")}>New agent</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="card">
        <table>
          <thead><tr><th>Name</th><th>Role</th><th>Specialization</th><th>Model</th><th>Status</th><th /></tr></thead>
          <tbody>
            {agents?.map((agent) => (
              <tr key={agent.id}>
                <td><strong>{agent.name}</strong></td>
                <td>{agent.role}</td>
                <td className="muted">{agent.specialization}</td>
                <td className="mono">{agent.model ?? <span className="muted">engine default</span>}</td>
                <td><span className={agent.active ? "badge badge-ok" : "badge"}>{agent.active ? "active" : "inactive"}</span></td>
                <td className="row">
                  <button className="btn btn-small" disabled={!agent.active} onClick={() => edit(agent)}>Edit</button>
                  <button className="btn btn-small" onClick={() => toggle(agent)}>{agent.active ? "Deactivate" : "Activate"}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {agents?.length === 0 && <div className="card-body muted">No agent yet.</div>}
      </div>
      {editing && (
        <AgentForm current={editing === "new" ? null : editing} models={models} onClose={() => setEditing(null)}
                   onSaved={() => { setEditing(null); reload(); }} />
      )}
    </div>
  );
}

function AgentForm({ current, models, onClose, onSaved }: {
  current: Versioned<Agent> | null; models: ModelInfo[]; onClose: () => void; onSaved: () => void;
}) {
  const api = useApi();
  const [name, setName] = useState(current?.body.name ?? "");
  const [role, setRole] = useState(current?.body.role ?? "");
  const [specialization, setSpecialization] = useState(current?.body.specialization ?? "");
  const [model, setModel] = useState(current?.body.model ?? "");
  const [problem, setProblem] = useState<unknown>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const body = { name, role, specialization, model: model || undefined };
    try {
      if (current) await api.updateAgent(current.body.id, current.etag, body);
      else await api.createAgent(body);
      onSaved();
    } catch (error) {
      setProblem(error);
    }
  }

  const errors = problem instanceof ApiProblem ? problem.errors : {};
  return (
    <Modal title={current ? `Edit ${current.body.name}` : "New agent"} onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="Name" error={errors.name}><input value={name} onChange={(e) => setName(e.target.value)} required autoFocus /></Field>
        <Field label="Role" error={errors.role}><input value={role} onChange={(e) => setRole(e.target.value)} required /></Field>
        <Field label="Specialization" error={errors.specialization} hint="Also what routing matches work against.">
          <input value={specialization} onChange={(e) => setSpecialization(e.target.value)} required />
        </Field>
        <Field label="Model" error={errors.model} hint="Empty means the engine's default.">
          <input list="engine-models" value={model} onChange={(e) => setModel(e.target.value)} placeholder="ollama:llama3.2:3b" />
          <datalist id="engine-models">{models.map((m) => <option key={m.id} value={m.id}>{m.available ? "" : "unavailable"}</option>)}</datalist>
        </Field>
        <ProblemNote problem={problem} />
        <div className="row"><span className="spacer" /><button className="btn btn-primary" type="submit">Save</button></div>
      </form>
    </Modal>
  );
}
