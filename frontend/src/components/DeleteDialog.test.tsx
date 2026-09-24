import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { DeleteDialog } from "./DeleteDialog";

const IMPACT = { kind: "TASK" as const, id: 7, name: "Scrivere il README", tasks: 1, runs: 2, handoffs: 1, reviews: 0,
  dailyItems: 1, phases: 0, planRuns: 0, resources: 0, runInProgress: false, folder: null,
  notes: ["1 voci del Daily Work restano, come voci personali con il titolo della task."] };

describe("DeleteDialog (ADR-027)", () => {
  it("shows what goes, and deletes only once the name is typed back", async () => {
    const onConfirm = vi.fn(async () => undefined);
    const loadImpact = vi.fn(async () => IMPACT);
    render(<DeleteDialog what="la task" name="Scrivere il README" loadImpact={loadImpact} onConfirm={onConfirm} onClose={vi.fn()} />);

    expect(await screen.findByText(/2 esecuzioni · 1 handoff · 0 review/)).toBeTruthy();
    const button = screen.getByRole("button", { name: "Elimina definitivamente" }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText("Conferma nome"), { target: { value: "scrivere il" } });
    expect(button.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Conferma nome"), { target: { value: "  scrivere il readme " } });
    expect(button.disabled).toBe(false);

    fireEvent.click(button);
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith("  scrivere il readme "));
    expect(loadImpact).toHaveBeenCalledTimes(1);
  });

  it("refuses while a run is in flight", async () => {
    render(<DeleteDialog what="la task" name="X" loadImpact={async () => ({ ...IMPACT, name: "X", runInProgress: true })}
                         onConfirm={vi.fn()} onClose={vi.fn()} />);
    expect(await screen.findByText(/Un'esecuzione è in corso/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Conferma nome"), { target: { value: "X" } });
    expect((screen.getByRole("button", { name: "Elimina definitivamente" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
