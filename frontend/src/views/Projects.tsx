import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useApi } from "../context";
import type { Project } from "../api/types";
import { Field, Modal, ProblemNote, when } from "../components/ui";

export function Projects() {
  const api = useApi();
  const [projects, setProjects] = useState<Project[] | null>(null);
  const [problem, setProblem] = useState<unknown>(null);
  const [creating, setCreating] = useState(false);

  const reload = useCallback(() => { api.projects().then(setProjects).catch(setProblem); }, [api]);
  useEffect(reload, [reload]);

  async function toggle(project: Project) {
    setProblem(null);
    try {
      const current = await api.project(project.id);
      await api.setProjectArchived(project.id, current.etag, current.body.status === "ACTIVE");
      reload();
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Projects</h1><p>Archiving freezes the tasks a project holds; restoring releases them. Nothing is deleted.</p></div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>New project</button>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      <div className="card">
        <table>
          <thead><tr><th>Name</th><th>Description</th><th>Status</th><th>Updated</th><th /></tr></thead>
          <tbody>
            {projects?.map((project) => (
              <tr key={project.id}>
                <td><strong>{project.name}</strong></td>
                <td className="muted">{project.description}</td>
                <td><span className={project.status === "ACTIVE" ? "badge badge-ok" : "badge"}>{project.status.toLowerCase()}</span></td>
                <td className="muted">{when(project.updatedAt)}</td>
                <td><button className="btn btn-small" onClick={() => toggle(project)}>{project.status === "ACTIVE" ? "Archive" : "Restore"}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        {projects?.length === 0 && <div className="card-body muted">No project yet.</div>}
      </div>
      {creating && <CreateProject onClose={() => setCreating(false)} onCreated={() => { setCreating(false); reload(); }} />}
    </div>
  );
}

function CreateProject({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const api = useApi();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [problem, setProblem] = useState<unknown>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      await api.createProject({ name, description: description || undefined });
      onCreated();
    } catch (error) {
      setProblem(error);
    }
  }

  return (
    <Modal title="New project" onClose={onClose}>
      <form className="stack" onSubmit={submit}>
        <Field label="Name"><input value={name} onChange={(e) => setName(e.target.value)} required autoFocus /></Field>
        <Field label="Description"><textarea value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        <ProblemNote problem={problem} />
        <div className="row"><span className="spacer" /><button className="btn btn-primary" type="submit">Create</button></div>
      </form>
    </Modal>
  );
}
