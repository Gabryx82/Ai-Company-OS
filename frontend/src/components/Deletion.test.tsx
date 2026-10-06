import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ApiProblem } from "../api/client";
import type { DeletionPreview } from "../api/types";
import { ConfirmDeletion, simplePreview } from "./Deletion";

const PREVIEW: DeletionPreview = {
  kind: "AGENT", subjects: [{ id: "7", name: "Backend Bot" }],
  deletes: ["L'agente e la sua configurazione."], detaches: ["2 task da fare restano, senza agente."],
  keeps: ["3 esecuzioni restano nello storico, con il nome dell'agente."], files: [], blockers: [], allowed: true,
};

describe("ConfirmDeletion (ADR-035 §3)", () => {
  it("shows what goes, what stays and what is kept, then deletes on confirm", async () => {
    const onConfirm = vi.fn(async () => undefined);
    const onClose = vi.fn();
    render(<ConfirmDeletion title="Elimina agente" subject="Backend Bot" loadPreview={async () => PREVIEW}
                            onConfirm={onConfirm} onClose={onClose} />);

    expect(screen.getByText(/Stai per eliminare definitivamente/)).toBeTruthy();
    expect(await screen.findByText("Viene eliminato")).toBeTruthy();
    expect(screen.getByText("2 task da fare restano, senza agente.")).toBeTruthy();
    expect(screen.getByText(/3 esecuzioni restano/)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Elimina$/ }));
    await waitFor(() => expect(onConfirm).toHaveBeenCalledTimes(1));
    expect(onClose).not.toHaveBeenCalled();
  });

  it("Annulla closes without deleting", async () => {
    const onConfirm = vi.fn(async () => undefined);
    const onClose = vi.fn();
    render(<ConfirmDeletion title="Elimina file" subject="a.png" preview={simplePreview("REFERENCE", ["Il file."], ["references/a.png"])}
                            onConfirm={onConfirm} onClose={onClose} />);
    expect(screen.getByText("references/a.png")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Annulla" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("a blocked delete says why and cannot be confirmed", async () => {
    render(<ConfirmDeletion title="Elimina agente" subject="Busy" onConfirm={vi.fn()} onClose={vi.fn()}
                            loadPreview={async () => ({ ...PREVIEW, allowed: false,
                              blockers: ["«Busy» lavora alla task in corso «X» (#3): riassegnala, completala o fermala prima."] })} />);
    expect(await screen.findByText(/Non si può eliminare adesso/)).toBeTruthy();
    expect(screen.getByText(/riassegnala/)).toBeTruthy();
    expect((screen.getByRole("button", { name: /Elimina$/ }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("a refusal of the server is shown and the dialog stays open", async () => {
    const onClose = vi.fn();
    render(<ConfirmDeletion title="Elimina agente" subject="Bot" preview={PREVIEW} onClose={onClose}
                            onConfirm={async () => { throw new ApiProblem(409, { type: "urn:ai-company-os:problem:deletion-blocked",
                              title: "Deletion blocked", detail: "Qualcuno ha appena avviato una run." }); }} />);
    fireEvent.click(screen.getByRole("button", { name: /Elimina$/ }));
    expect(await screen.findByText("Qualcuno ha appena avviato una run.")).toBeTruthy();
    expect(screen.getByRole("dialog")).toBeTruthy();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("asks for the name when the delete is heavy, and offers the reversible gesture", async () => {
    const onConfirm = vi.fn(async () => undefined);
    const archive = vi.fn(async () => undefined);
    render(<ConfirmDeletion title="Elimina Blueprint" subject="il Blueprint" typedName="Shop" preview={PREVIEW}
                            alternative={{ label: "Disattiva", hint: "È reversibile.", onClick: archive }}
                            onConfirm={onConfirm} onClose={vi.fn()} />);
    const button = screen.getByRole("button", { name: /Elimina$/ }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Conferma nome"), { target: { value: " shop " } });
    expect(button.disabled).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Disattiva" }));
    await waitFor(() => expect(archive).toHaveBeenCalledTimes(1));
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
