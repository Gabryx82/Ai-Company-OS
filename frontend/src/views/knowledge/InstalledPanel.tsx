import { useMemo, useState } from "react";
import type { HubApp, HubInstalled, HubKind, HubScope, HubSkill } from "../../api/types";
import { Icon } from "../../components/icons";
import {
  belongsTo, COPY_LABEL, countBy, KIND_LABEL, KIND_ORDER, kindOf, matches, NO_FILTERS, presence, presenceKey, readersByLocation,
  SCOPE_HINT, SCOPE_LABEL, SCOPE_ORDER, sections, type Filters, type StatusFilter,
} from "./hubModel";
import { FoundSkillDetail } from "./FoundSkillDetail";

const PAGE = 12;

/**
 * Everything installed for the operator's agents in one place (PHASE 31): the
 * applications on the left; on the right their plugins, connectors, skills and
 * knowledge, grouped by application, kind and type; a detail panel for the one
 * selected. Library entries open the library's own editor, passed in as
 * {@code renderLibraryDetail}.
 */
export function InstalledPanel({ installed, onChanged, renderLibraryDetail }: {
  installed: HubInstalled;
  onChanged: (message: string) => void;
  renderLibraryDetail: (key: string, close: () => void) => React.ReactNode;
}) {
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [selected, setSelected] = useState<HubSkill | null>(null);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  const skills = installed.skills ?? [];
  const apps = installed.apps ?? [];
  const readers = useMemo(() => readersByLocation(apps), [apps]);
  const visible = useMemo(() => skills.filter((s) => matches(s, filters, readers)), [skills, filters, readers]);
  const grouped = useMemo(() => sections(apps, visible, filters.app), [apps, visible, filters.app]);
  const families = useMemo(() => presence(skills), [skills]);
  const appName = (id: string) => apps.find((a) => a.id === id)?.name ?? id;
  const byApp = useMemo(() => new Map(apps.map((a) => [a.id, skills.filter((s) => belongsTo(s, a.id, readers)).length])),
    [apps, skills, readers]);
  const inApp = filters.app ? skills.filter((s) => belongsTo(s, filters.app!, readers)) : skills;
  const byKind = countBy(inApp, (s) => kindOf(s));
  const ofKind = filters.kind ? inApp.filter((s) => kindOf(s) === filters.kind) : inApp;
  const byScope = countBy(ofKind, (s) => s.scope);
  const byCategory = countBy(ofKind, (s) => s.category);
  const selectedApp = filters.app ? apps.find((a) => a.id === filters.app) ?? null : null;
  const set = (patch: Partial<Filters>) => setFilters((f) => ({ ...f, ...patch }));

  const present = apps.filter((a) => a.detected || (byApp.get(a.id) ?? 0) > 0);
  const absent = apps.filter((a) => !present.includes(a));

  return (
    <div className="hub-layout">
      <nav className="hub-apps" aria-label="Applicazioni">
        <button className="hub-app" aria-pressed={filters.app === null} onClick={() => set({ app: null })}>
          <Icon name="knowledge" size={15} /> Tutte le app <span className="count">{skills.length}</span>
        </button>
        {present.map((app) => (
          <button key={app.id} className="hub-app" aria-pressed={filters.app === app.id}
                  onClick={() => set({ app: app.id, scope: "" })} title={app.description}>
            <span className={app.detected ? "hub-dot" : "hub-dot off"} /> {app.name}
            <span className="count">{byApp.get(app.id) ?? 0}</span>
          </button>
        ))}
        {absent.length > 0 && <div className="section-title" style={{ marginTop: 10 }}>Non trovate su questo PC</div>}
        {absent.map((app) => (
          <button key={app.id} className="hub-app is-absent" aria-pressed={filters.app === app.id}
                  onClick={() => set({ app: app.id, scope: "" })} title={app.description}>
            <span className="hub-dot off" /> {app.name}
          </button>
        ))}
      </nav>

      <div className="stack">
        {selectedApp && <AppHeader app={selectedApp} counts={byKind} />}
        <div className="card card-body stack" style={{ gap: 10 }}>
          <div className="tabs" role="group" aria-label="Tipo">
            <button className="tab" aria-selected={filters.kind === ""} onClick={() => set({ kind: "", scope: "" })}>
              Tutto ({inApp.length})</button>
            {KIND_ORDER.filter((k) => byKind.has(k)).map((kind) => (
              <button key={kind} className="tab" aria-selected={filters.kind === kind}
                      onClick={() => set({ kind: kind as HubKind, scope: "" })}>{KIND_LABEL[kind]} ({byKind.get(kind)})</button>
            ))}
          </div>
          <div className="row">
            <div className="search" style={{ flex: 1, minWidth: 220 }}><Icon name="search" size={15} />
              <input aria-label="Cerca tra le skill installate" placeholder="Cerca per nome, descrizione, plugin, connettore…"
                     value={filters.query} onChange={(e) => set({ query: e.target.value })} /></div>
            <select aria-label="Categoria" className="hub-select" value={filters.category} onChange={(e) => set({ category: e.target.value })}>
              <option value="">Tutte le categorie</option>
              {(installed.categories ?? []).filter((c) => byCategory.has(c.key)).map((c) => (
                <option key={c.key} value={c.key}>{c.label} ({byCategory.get(c.key)})</option>
              ))}
            </select>
            <select aria-label="Stato" className="hub-select" value={filters.status} onChange={(e) => set({ status: e.target.value as StatusFilter })}>
              <option value="">Qualsiasi stato</option>
              <option value="updates">Con aggiornamenti</option>
              <option value="copies">Copie sincronizzate dal Hub</option>
              <option value="disabled">Disattivate nell'app</option>
            </select>
          </div>
          <div className="tabs" role="group" aria-label="Tipologia">
            <button className="tab" aria-selected={filters.scope === ""} onClick={() => set({ scope: "" })}>Tutte le tipologie</button>
            {SCOPE_ORDER.filter((s) => byScope.has(s)).map((scope) => (
              <button key={scope} className="tab" aria-selected={filters.scope === scope} title={SCOPE_HINT[scope]}
                      onClick={() => set({ scope: scope as HubScope })}>{SCOPE_LABEL[scope]} ({byScope.get(scope)})</button>
            ))}
          </div>
        </div>

        <div className={selected ? "split hub-split" : ""}>
          <div className="stack">
            {grouped.length === 0 && <div className="card card-body muted">Niente corrisponde ai filtri.</div>}
            {grouped.map((section) => (
              <section key={section.app.id} className="stack" style={{ gap: 6 }} aria-label={section.app.name}>
                {!filters.app && (
                  <div className="row hub-app-title">
                    <h2>{section.app.name}</h2><span className="muted">{section.total}</span>
                    {!section.app.detected && <span className="badge">non trovata</span>}
                  </div>
                )}
                {section.groups.map((group) => {
                  const isCollapsed = collapsed[group.key] ?? false;
                  const showAll = expanded[group.key] ?? false;
                  const shown = showAll ? group.skills : group.skills.slice(0, PAGE);
                  return (
                    <div key={group.key}>
                      <button className="hub-group-head" aria-expanded={!isCollapsed}
                              onClick={() => setCollapsed((c) => ({ ...c, [group.key]: !isCollapsed }))}>
                        <span className="hub-caret">{isCollapsed ? "▸" : "▾"}</span>
                        <strong>{group.title}</strong>
                        {group.subtitle && <span className="muted">{group.subtitle}</span>}
                        <span className="chip">{group.skills.length}</span>
                      </button>
                      {!isCollapsed && (
                        <>
                          <div className="hub-cards">
                            {shown.map((skill) => (
                              <ItemCard key={skill.id} skill={skill} selected={selected?.id === skill.id}
                                        alsoIn={(families.get(presenceKey(skill)) ?? []).filter((a) => a !== skill.app).map(appName)}
                                        onSelect={() => setSelected(skill)} />
                            ))}
                          </div>
                          {group.skills.length > PAGE && (
                            <button className="btn btn-small" style={{ marginTop: 8 }}
                                    onClick={() => setExpanded((e) => ({ ...e, [group.key]: !showAll }))}>
                              {showAll ? "Mostra meno" : `Mostra tutti (${group.skills.length})`}</button>
                          )}
                        </>
                      )}
                    </div>
                  );
                })}
              </section>
            ))}
            {(installed.notes ?? []).length > 0 && (
              <div className="muted" style={{ fontSize: 12 }}>{installed.notes.map((n) => <div key={n}>{n}</div>)}</div>
            )}
          </div>
          {selected && (selected.app === "aicos" && selected.libraryKey
            ? renderLibraryDetail(selected.libraryKey, () => setSelected(null))
            : <FoundSkillDetail key={selected.id} skill={selected} apps={apps} onClose={() => setSelected(null)}
                                onChanged={(message) => { onChanged(message); }} />)}
        </div>
      </div>
    </div>
  );
}

function AppHeader({ app, counts }: { app: HubApp; counts: Map<string, number> }) {
  return (
    <div className="card card-body stack" style={{ gap: 8 }}>
      <div className="row">
        <span className={app.detected ? "hub-dot" : "hub-dot off"} /><h2 style={{ margin: 0 }}>{app.name}</h2>
        <span className={app.detected ? "badge badge-ok" : "badge"}>{app.detected ? "su questo PC" : "non trovata"}</span>
        {app.installable && <span className="badge badge-accent" title="Il Knowledge Hub può copiarci le skill della libreria">installabile dal Hub</span>}
        <span className="spacer" />
        {KIND_ORDER.filter((k) => counts.has(k)).map((k) => <span key={k} className="chip">{counts.get(k)} {KIND_LABEL[k].toLowerCase()}</span>)}
      </div>
      <div className="muted" style={{ fontSize: 12.5 }}>{app.description}</div>
      <div className="hub-locations">
        {(app.locations ?? []).map((l) => (
          <div key={l.id} className="row" style={{ fontSize: 12 }}>
            <Icon name={l.writable ? "folder" : "file"} size={12} />
            <strong>{l.label}</strong>
            <code className="muted">{l.path}</code>
            <span className="chip">{l.count}</span>
            {!l.exists && <span className="muted">(assente)</span>}
            {l.writable ? <span className="badge badge-ok">scrivibile</span> : <span className="badge">gestita dall'app</span>}
            {l.readers.length > 1 && <span className="muted">letta da: {l.readers.join(", ")}</span>}
          </div>
        ))}
      </div>
      {(app.agents ?? []).length > 0 && (
        <div style={{ fontSize: 12.5 }}>
          <span className="muted">Agenti che lavorano qui: </span>
          {app.agents.map((a) => <span key={a.id} className="chip">{a.name}</span>)}
        </div>
      )}
    </div>
  );
}

/** What a plugin is made of, in a few words: "21 skill · 2 comandi · 1 agente · hook · 1 connettore". */
function components(details: Record<string, string>): string[] {
  const out: string[] = [];
  const n = (label: string) => Number.parseInt(details[label] ?? "0", 10) || 0;
  if (n("Skill") > 0) out.push(`${n("Skill")} skill`);
  if (n("Comandi") > 0) out.push(`${n("Comandi")} ${n("Comandi") === 1 ? "comando" : "comandi"}`);
  if (n("Agenti") > 0) out.push(`${n("Agenti")} ${n("Agenti") === 1 ? "agente" : "agenti"}`);
  if (details.Hook === "sì") out.push("hook");
  if (n("Connettori") > 0) out.push(`${n("Connettori")} ${n("Connettori") === 1 ? "connettore" : "connettori"}`);
  return out;
}

function ItemCard({ skill, selected, alsoIn, onSelect }: {
  skill: HubSkill; selected: boolean; alsoIn: string[]; onSelect: () => void;
}) {
  const kind = kindOf(skill);
  const details = skill.details ?? {};
  const copy = skill.copyStatus ? COPY_LABEL[skill.copyStatus] : null;
  return (
    <button className={`agent-card hub-card hub-card-${kind.toLowerCase()}`} aria-pressed={selected} onClick={onSelect}>
      <div className="row" style={{ gap: 6 }}>
        {kind === "PLUGIN" && <span className="chip hub-kind">plugin</span>}
        {kind === "CONNECTOR" && <span className="chip hub-kind">MCP</span>}
        <strong style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{skill.name}</strong>
        <span className="spacer" />
        {skill.updateAvailable && <span className="badge badge-warn" title="Aggiornamento disponibile">aggiornamento</span>}
      </div>
      {skill.description && <small className="muted desc">{skill.description}</small>}
      <div>
        {kind === "PLUGIN" && components(details).map((c) => <span key={c} className="chip">{c}</span>)}
        {kind === "PLUGIN" && skill.pluginVersion && <span className="chip">v{skill.pluginVersion}</span>}
        {kind === "CONNECTOR" && details["Fornito da"] && <span className="chip" title={details["Fornito da"]}>
          {details["Fornito da"].startsWith("plugin ") ? `da ${details["Fornito da"].split(", ").length} plugin` : details["Fornito da"]}</span>}
        {kind !== "PLUGIN" && kind !== "CONNECTOR" && <span className="chip">{skill.categoryLabel}</span>}
        {kind === "KNOWLEDGE" && <span className="chip">knowledge</span>}
        {kind === "SKILL" && skill.version && <span className="chip">v{skill.version}</span>}
        {copy && <span className={copy.tone} title="Copia di una skill della libreria, scritta dal Knowledge Hub">copia del Hub · {copy.label}</span>}
        {!skill.copyStatus && skill.libraryKey && skill.app !== "aicos" && <span className="badge badge-ok">in libreria</span>}
        {skill.provenance && <span className="chip" title={skill.provenance.reference}>da {skill.provenance.providerName}</span>}
        {(skill.copies ?? []).length > 0 && <span className="badge badge-accent">in {(skill.copies ?? []).length} app</span>}
        {skill.enabled === false && <span className="badge">{kind === "PLUGIN" || kind === "CONNECTOR" ? "disattivato" : "disattivata"}</span>}
        {alsoIn.length > 0 && <span className="chip" title="Lo stesso nome è anche in queste app">anche in: {alsoIn.join(", ")}</span>}
      </div>
    </button>
  );
}
