import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { Usage } from "./Usage";

const IN_TWO_HOURS = new Date(Date.now() + 2 * 3_600_000 + 60_000).toISOString();

function windowOf(key: string, subject: string, extra: Record<string, unknown>) {
  return { key, name: key, subject, source: "MANUAL", windowKind: "DAILY", status: "NO_DATA", usedPercent: null,
    tokens: null, outputTokens: null, windowStart: null, nextResetAt: null, observedAt: null, detail: null, byModel: [],
    modelQuotas: [], resetWeekday: null, resetTime: null, resetZone: null, limitNote: null, usageUrl: "https://example.org",
    version: 0, ...extra };
}

describe("Consumi e quote — Antigravity, Kimi Code, Copilot (ADR-038)", () => {
  it("shows every Antigravity model with its share and reset, and says what Kimi Code needs", async () => {
    const { fetcher } = fakeFetch({
      "GET /api/usage": () => ({ body: [
        windowOf("antigravity-models", "antigravity", { source: "ANTIGRAVITY_LOCAL", windowKind: "PER_MODEL",
          status: "MEASURED", usedPercent: 60, nextResetAt: IN_TWO_HOURS, modelQuotas: [
            { model: "Gemini 3.1 Pro (High)", usedPercent: 60, resetsAt: IN_TWO_HOURS, resetSinceObservation: false },
            { model: "Claude Opus 4.6 (Thinking)", usedPercent: 0, resetsAt: null, resetSinceObservation: true },
          ] }),
        windowOf("kimi-code-weekly", "kimi", { source: "KIMI_CODE_API", windowKind: "WEEKLY", status: "CONFIGURATION_NEEDED",
          detail: "Aggiungi la tua chiave Kimi Code come AICOS_KIMI_CODE_API_KEY nel file local.env" }),
        windowOf("copilot-monthly", "copilot", { windowKind: "MONTHLY", nextResetAt: IN_TWO_HOURS, resetZone: "UTC" }),
      ] }),
    });
    const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
    render(<ApiContext.Provider value={api}><Usage /></ApiContext.Provider>);

    expect(await screen.findByRole("heading", { name: "Antigravity" })).toBeTruthy();
    const models = screen.getByRole("list", { name: /Quote dei modelli/ });
    const rows = within(models).getAllByRole("listitem");
    expect(rows[0].textContent).toMatch(/Gemini 3\.1 Pro \(High\).*60%.*reset tra 2h 0\dm/);
    expect(rows[1].textContent).toMatch(/Claude Opus 4\.6 \(Thinking\).*0%.*azzerata/);
    expect(screen.getByText("Per modello")).toBeTruthy();

    expect(screen.getByRole("heading", { name: "Kimi Code" })).toBeTruthy();
    expect(screen.getByText(/AICOS_KIMI_CODE_API_KEY/)).toBeTruthy();
    expect(screen.getByRole("heading", { name: "GitHub Copilot" })).toBeTruthy();
  });
});
