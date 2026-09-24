import { useState } from "react";
import { displayName, setDisplayName } from "../preferences";
import { Field } from "../components/ui";

/** Per-viewer preferences. Nothing here reaches the control plane. */
export function Settings() {
  const [name, setName] = useState(displayName());
  const [saved, setSaved] = useState(false);

  return (
    <div className="stack" style={{ maxWidth: 560 }}>
      <div className="page-head"><div><h1>Impostazioni</h1><p>Preferenze di questa console, salvate in questo browser.</p></div></div>
      <div className="card card-body stack">
        <Field label="Nome visualizzato" hint="Usato nel saluto della dashboard.">
          <input value={name} onChange={(e) => { setName(e.target.value); setSaved(false); }} />
        </Field>
        <div className="row"><span className="spacer" />{saved && <span className="badge badge-ok">Salvato</span>}
          <button className="btn btn-primary" onClick={() => { setDisplayName(name); setSaved(true); }}>Salva</button></div>
      </div>
    </div>
  );
}
