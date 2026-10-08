import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane, type Session } from "../../api/client";
import type { HubResult, HubSkill } from "../../api/types";
import { ApiContext, SessionContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import { ExtensionInstallDialog } from "./ExtensionInstallDialog";
import { FoundSkillDetail } from "./FoundSkillDetail";

const SESSION: Session = { baseUrl: "http://localhost:8081", token: "t0123456789abcdef",
  user: { id: 1, username: "admin", displayName: null, role: "ADMIN" } as Session["user"] };

function mount(routes: Parameters<typeof fakeFetch>[0], ui: React.ReactNode) {
  const { fetcher, calls } = fakeFetch(routes);
  render(
    <SessionContext.Provider value={SESSION}>
      <ApiContext.Provider value={new ControlPlane(SESSION, fetcher)}>{ui}</ApiContext.Provider>
    </SessionContext.Provider>,
  );
  return calls;
}

const PLUGIN: HubResult = { provider: "claude-plugins-official", ref: "frontend-design", name: "frontend-design", description: null,
  author: null, source: null, installs: null, version: null, url: null, suspicious: false, kind: "PLUGIN", targets: ["claude-code"] };

/** PHASE 32 (TD-88): a plugin's options are asked in the dialog and sent with the install, never kept. */
describe("installing a plugin with options", () => {
  it("asks for the required option before installing, and sends what was given", async () => {
    const calls = mount({
      "GET /api/skill-hub/marketplaces/claude-plugins-official/item": () => ({ body: {
        provider: "claude-plugins-official", ref: "frontend-design", kind: "PLUGIN", name: "frontend-design", installName: "frontend-design",
        description: null, version: "1.0.0", sourceUrl: null, details: {},
        inputs: [{ name: "api_token", kind: "CONFIG", required: true, secret: true, description: "Token", defaultValue: null },
          { name: "region", kind: "CONFIG", required: false, secret: false, description: null, defaultValue: "eu" }],
        targets: [{ app: "claude-code", appName: "Claude Code", available: true, method: "CLI", command: "claude plugin install", note: null }],
        warnings: [] } }),
      "POST /api/skill-hub/plugins/install": () => ({ body: { target: "claude-code", status: "INSTALLED", message: "Installato in Claude Code",
        command: null, output: null, confirmation: null, software: null } }),
    }, <ExtensionInstallDialog result={PLUGIN} installed={null} providerName="Claude Code · plugin ufficiali"
                              onClose={vi.fn()} onInstalled={vi.fn()} />);

    const dialog = await screen.findByRole("dialog");
    const install = await within(dialog).findByRole("button", { name: "Installa" }) as HTMLButtonElement;
    expect(install.disabled).toBe(true);
    expect(within(dialog).getAllByText(/opzione del plugin/).length).toBeGreaterThan(0);
    fireEvent.change(within(dialog).getByLabelText(/api_token/), { target: { value: "tok-123456" } });
    expect(install.disabled).toBe(false);
    fireEvent.click(install);

    await waitFor(() => expect(calls.some((c) => c.path === "/api/skill-hub/plugins/install")).toBe(true));
    expect(calls.find((c) => c.path === "/api/skill-hub/plugins/install")!.body).toEqual({
      provider: "claude-plugins-official", ref: "frontend-design", target: "claude-code", values: { api_token: "tok-123456" } });
  });
});

const CONNECTOR: HubSkill = {
  id: "c42", app: "claude-code", location: "claude-code.connectors", scope: "PERSONAL", kind: "CONNECTOR", name: "files",
  description: "stdio · npx", category: "connettori", categoryLabel: "Connettori e servizi", plugin: null, pluginVersion: null,
  marketplace: null, enabled: null, version: null, path: null, writable: false, modified: null, libraryKey: null, copyStatus: null,
  provenance: null, copies: [], updateAvailable: false, details: { Tipo: "stdio", "Fornito da": "configurato da te" },
};

/** PHASE 32 (TD-90): an admin takes a connector out of its application from its detail. */
describe("taking a connector out of its application", () => {
  it("asks first, then removes it and says what happened", async () => {
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    const changed = vi.fn();
    const calls = mount({
      "GET /api/skill-hub/installed/c42": () => ({ body: { skill: CONNECTOR, content: "files", truncated: false, files: [], folder: "" } }),
      "DELETE /api/skill-hub/installed/c42": () => ({ body: { target: "claude-code", status: "REMOVED",
        message: "Connettore «files» tolto da Claude Code", command: "claude mcp remove files -s user", output: null,
        confirmation: null, software: null } }),
    }, <FoundSkillDetail skill={CONNECTOR} apps={[]} onClose={vi.fn()} onChanged={changed} />);

    fireEvent.click(await screen.findByRole("button", { name: /Togli da claude-code/ }));
    await waitFor(() => expect(changed).toHaveBeenCalledWith("Connettore «files» tolto da Claude Code"));
    expect(confirm).toHaveBeenCalled();
    expect(calls.some((c) => c.method === "DELETE" && c.path === "/api/skill-hub/installed/c42")).toBe(true);
    confirm.mockRestore();
  });
});
