import { describe, expect, it } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { MeasuresCard } from "./MeasuresCard";

const MEASURED = {
  runId: 7, taskId: 3, taskTitle: "Schema dei prestiti", agentId: 2, agentName: "Database Specialist",
  model: "ollama:qwen3.5:9b", status: "SUCCEEDED", inputTokens: 1200, outputTokens: 80, latencyMs: 9950,
  providerTotalMs: 9877, loadMs: 2500, promptEvalMs: 4000, evalMs: 3300, contextWindow: 16384,
  systemChars: 2400, userChars: 5300, finishedAt: "2026-11-07T10:00:00Z",
};
const ECHO = {
  ...MEASURED, runId: 6, model: "echo:default", inputTokens: 7, outputTokens: 7, latencyMs: 1,
  providerTotalMs: null, loadMs: null, promptEvalMs: null, evalMs: null, contextWindow: null,
};
const GROUP = {
  key: "ollama:qwen3.5:9b", agentId: null, runs: 2, timedRuns: 2, inputTokens: 1800, outputTokens: 120,
  avgInputTokens: 900, avgOutputTokens: 60, avgProviderTotalMs: 6377, avgLoadMs: 1250, avgPromptEvalMs: 2500,
  avgEvalMs: 2500, outputTokensPerSecond: 24, contextWindows: [16384], avgSystemChars: 2400, avgUserChars: 5300,
};
const UNTIMED = {
  ...GROUP, key: "echo:default", runs: 1, timedRuns: 0, avgProviderTotalMs: null, avgLoadMs: null,
  avgPromptEvalMs: null, avgEvalMs: null, outputTokensPerSecond: null, contextWindows: [],
};

function renderWith(report: unknown) {
  const { fetcher } = fakeFetch({ "GET /api/usage/measurements": () => ({ body: report }) });
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  render(<ApiContext.Provider value={api}><MeasuresCard /></ApiContext.Provider>);
}

describe("Misure degli agenti (ADR-041, fase 1)", () => {
  it("shows per model what was measured, and a dash where nothing was", async () => {
    renderWith({ from: "2026-11-01", to: "2026-11-07", runs: [MEASURED, ECHO], byModel: [GROUP, UNTIMED],
      byAgent: [{ ...GROUP, key: "Database Specialist", agentId: 2 }], handoffs: [] });

    const table = await screen.findByRole("table", { name: "Misure per modello" });
    const [, qwen, echo] = within(table).getAllByRole("row");
    expect(qwen.textContent).toMatch(/ollama:qwen3\.5:9b.*2 di 2.*900.*60.*6,4 s.*1,3 s.*24.*16\.384/);
    // The echo has tokens, but no durations: no number stands in for one.
    expect(within(echo).getAllByText("—").length).toBeGreaterThanOrEqual(4);
    expect(echo.textContent).toMatch(/0 di 1/);
    expect(screen.getByText(/non sono convertiti in token/)).toBeTruthy();
    // The period is the server's calendar days, whatever the browser's time zone.
    expect(screen.getByText("1/11/2026 – 7/11/2026")).toBeTruthy();
  });

  it("lists each run with its own durations and the size of what it sent", async () => {
    renderWith({ from: "2026-11-01", to: "2026-11-07", runs: [MEASURED], byModel: [GROUP], byAgent: [], handoffs: [] });

    fireEvent.click(await screen.findByRole("tab", { name: "Esecuzioni" }));
    const row = within(screen.getByRole("table", { name: "Misure per esecuzione" })).getAllByRole("row")[1];
    expect(row.textContent).toMatch(/Schema dei prestiti.*Database Specialist.*1200 \/ 80.*2,5 s.*4,0 s.*3,3 s.*9,9 s.*16\.384.*2400 \/ 5300/);
  });

  it("gives each handoff its package and what it asks to read, a missing file said as such", async () => {
    renderWith({ from: "2026-11-01", to: "2026-11-07", runs: [], byModel: [], byAgent: [], handoffs: [
      { handoffId: 4, taskId: 3, taskTitle: "Schema dei prestiti", target: "claude-code", createdAt: "2026-11-07T09:00:00Z",
        packageChars: 3150, requiredChars: 9800, optionalChars: 1200, missingFiles: 1, reads: [
          { kind: "GOVERNANCE", path: "AGENTS.md", required: true, chars: 2300 },
          { kind: "PHASE", path: "docs/phases/PHASE_1.md", required: true, chars: null },
          { kind: "SKILL", path: "C:\\lib\\skills\\sql\\SKILL.md", required: true, chars: 7500 },
        ] },
      { handoffId: 2, taskId: 1, taskTitle: "Prima delle misure", target: "codex", createdAt: "2026-11-01T09:00:00Z",
        packageChars: null, requiredChars: null, optionalChars: null, missingFiles: 0, reads: [] },
    ] });

    fireEvent.click(await screen.findByRole("tab", { name: "Handoff" }));
    const rows = within(screen.getByRole("table", { name: "Misure per handoff" })).getAllByRole("row");
    expect(rows[1].textContent).toMatch(/Schema dei prestiti.*claude-code.*3150.*9800.*1200.*3 file, 1 mancante/);
    expect(rows[2].textContent).toMatch(/Prima delle misure.*non misurato/);
    fireEvent.click(screen.getByText("Schema dei prestiti — file chiesti"));
    expect(screen.getByText("docs/phases/PHASE_1.md").closest("li")!.textContent).toMatch(/mancante/);
    expect(screen.getByText("C:\\lib\\skills\\sql\\SKILL.md").closest("li")!.textContent).toMatch(/7500/);
  });
});
