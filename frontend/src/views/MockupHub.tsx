import { useEffect, useState } from "react";
import { useApi } from "../context";
import type { Project, Resource } from "../api/types";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

/**
 * The Template / Mockup Hub (directive §21): one search across the template
 * providers of the catalog, each opened in its own window -- none of them lets
 * itself be framed, and none offers a download API without an account. What you
 * pick goes into the project's references/, where agents read it.
 */
export function MockupHub() {
  const api = useApi();
  const [providers, setProviders] = useState<Resource[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [query, setQuery] = useState("");
  const [problem, setProblem] = useState<unknown>(null);

  useEffect(() => {
    api.resources("TEMPLATE_PROVIDER").then(setProviders).catch(setProblem);
    api.projects().then((p) => setProjects(p.filter((x) => x.status === "ACTIVE" && x.workspacePath))).catch(() => undefined);
  }, [api]);

  function open(provider: Resource) {
    const url = query.trim() && provider.searchUrl
      ? provider.searchUrl.replace("{q}", encodeURIComponent(query.trim()))
      : provider.sourceUrl;
    if (!url) return;
    const w = window.open(url, `aicos-${provider.key}`);
    if (w) w.opener = null;
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div><h1>Mockup Hub</h1><p>Cerca mockup, UI kit, template e reference fra i provider; salva ciò che scegli nelle reference del progetto.</p></div>
      </div>
      <ProblemNote problem={problem} />
      <div className="card card-body stack">
        <div className="search" style={{ maxWidth: 520 }}><Icon name="search" size={15} />
          <input placeholder="dashboard dark, e-commerce mobile, ui kit fintech…" value={query}
                 onChange={(e) => setQuery(e.target.value)}
                 onKeyDown={(e) => { if (e.key === "Enter" && providers[0]) open(providers[0]); }} /></div>
        <div className="muted" style={{ fontSize: 12.5 }}>Premi un provider per cercare lì. Quelli senza ricerca diretta si aprono sulla loro pagina.</div>
      </div>
      <div className="tile-grid">
        {providers.map((p) => (
          <button key={p.key} className="tile" onClick={() => open(p)} title={p.description ?? ""}>
            <Icon name="mockups" size={26} />
            <h3>{p.name}</h3>
            <span className="role">{p.description}</span>
            <span className="chip">{p.searchUrl ? "ricerca diretta" : "apre il sito"}</span>
          </button>
        ))}
      </div>
      {projects.length > 0 && (
        <div className="card card-body">
          <div className="section-title">Dove salvare</div>
          {projects.map((p) => <div key={p.id} className="mono" style={{ fontSize: 12.5 }}>{p.name}: {p.workspacePath}\references\mockups\</div>)}
        </div>
      )}
    </div>
  );
}
