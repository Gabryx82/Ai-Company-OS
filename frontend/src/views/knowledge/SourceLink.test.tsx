import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ControlPlane } from "../../api/client";
import type { HubSkill } from "../../api/types";
import { ApiContext } from "../../context";
import { fakeFetch } from "../../test/fakeServer";
import { SourceLink } from "./SourceLink";

const PDF: HubSkill = {
  id: "aicos:pdf", app: "aicos", location: "aicos.library", scope: "LIBRARY", kind: "SKILL", name: "pdf",
  description: null, category: "documenti", categoryLabel: "Documenti e Office", plugin: null, pluginVersion: null,
  marketplace: null, enabled: null, version: null, path: null, writable: true, modified: null, libraryKey: "pdf",
  copyStatus: null, copies: [], updateAvailable: false, details: null,
  provenance: { provider: "app", providerName: "App: Verdent", reference: "verdent.personal:pdf/SKILL.md", sourceUrl: null,
    installedVersion: null, latestVersion: null, installedAt: "2026-10-08T10:00:00Z", checkedAt: null, updateAvailable: false,
    localChanges: false, checkError: null },
};

/** PHASE 32 (TD-80): a library skill imported from an app is tied to its package online, found by name. */
describe("SourceLink", () => {
  it("finds the packages with the skill's name and ties the skill to the one chosen", async () => {
    const { fetcher, calls } = fakeFetch({
      "GET /api/skill-hub/marketplaces/search": () => ({ body: { errors: [], results: [
        { provider: "anthropic-skills", ref: "anthropics/skills:skills/pdf", name: "pdf", description: null, author: null,
          source: null, installs: null, version: null, url: null, suspicious: false },
      ] } }),
      "PUT /api/skill-hub/library/pdf/source": () => ({ body: { key: "pdf", provider: "anthropic-skills",
        reference: "anthropics/skills:skills/pdf", version: "a1", inStep: true, message: "«pdf» collegata: è alla versione più recente" } }),
    });
    const changed = vi.fn();
    render(<ApiContext.Provider value={new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher)}>
      <SourceLink skill={PDF} onChanged={changed} /></ApiContext.Provider>);

    expect(screen.getByText("App: Verdent · verdent.personal:pdf/SKILL.md")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Collega a una fonte online" }));
    fireEvent.click(await screen.findByRole("button", { name: "Collega a anthropic-skills anthropics/skills:skills/pdf" }));

    await waitFor(() => expect(changed).toHaveBeenCalledWith("«pdf» collegata: è alla versione più recente"));
    expect(calls.find((c) => c.method === "GET")!.path).toContain("q=pdf");
    expect(calls.find((c) => c.method === "PUT")!.body).toEqual({ provider: "anthropic-skills", ref: "anthropics/skills:skills/pdf" });
  });
});
