import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ControlPlane } from "../api/client";
import { ApiContext } from "../context";
import { fakeFetch } from "../test/fakeServer";
import { Learn, lessonRoute } from "./Learn";

const PATH = "lessons/TASK-001.lezione.md";
const CODE = "public record Prestito(String libro) {\n    boolean scaduto() { return true; }\n}";

/** A lesson as the control plane gives it: the answers arrive only with what the operator has done. */
function lesson(state: { predicted?: boolean; written?: boolean; quiz?: boolean } = {}) {
  const done = (state.predicted ? 1 : 0) + (state.written ? 1 : 0) + (state.quiz ? 1 : 0);
  return {
    path: PATH, title: "Il modello di un prestito", task: "TASK-001", intro: "Un prestito è una scheda.\n\nCome in biblioteca.",
    prediction: { question: "Il prestito di ieri è scaduto?", answered: !!state.predicted,
      yourAnswer: state.predicted ? "Secondo me sì" : null, answer: state.predicted ? "Sì: oggi è dopo ieri." : null },
    steps: [{ index: 0, title: "La scheda", file: "src/Prestito.java", mode: "NEW", language: "java", code: CODE,
      lines: [{ code: "boolean scaduto()", text: "dice se il prestito è scaduto" }, { code: null, text: "Tutto sta in tre righe." }],
      why: "Un record non cambia.", done: !!state.written }],
    exercises: [{ index: 0, kind: "QUIZ", prompt: "Che cosa restituisce `scaduto`?", options: ["true", "false"], language: null,
      code: null, attempts: state.quiz ? 2 : 0, done: !!state.quiz, firstTryRight: state.quiz ? false : null, yourAnswer: null,
      solution: state.quiz ? "0" : null, explanation: state.quiz ? "Qui è sempre vero." : null, keyPoints: [] }],
    concepts: done === 3 ? [{ name: "Record", text: "un tipo che non cambia" }] : [],
    problems: [], items: 3, done, completed: done === 3,
  };
}

function mount(routes: Parameters<typeof fakeFetch>[0], selected: string | null) {
  const { fetcher, calls } = fakeFetch(routes);
  const api = new ControlPlane({ baseUrl: "http://localhost:8081", token: "t0123456789abcdef" }, fetcher);
  const navigate = vi.fn();
  render(<ApiContext.Provider value={api}><Learn selected={selected} navigate={navigate} /></ApiContext.Provider>);
  return { calls, navigate };
}

describe("Impara — the page", () => {
  const overview = (extra: Record<string, unknown> = {}) => ({
    projects: [{ projectId: 5, projectName: "Biblioteca", lessons: [
      { path: PATH, title: "Il modello di un prestito", task: "TASK-001", items: 8, done: 3, completed: false, problems: [],
        modified: "2026-11-10T10:00:00Z" },
      { path: "lessons/TASK-000.lezione.md", title: "Lo scheletro", task: "TASK-000", items: 4, done: 4, completed: true,
        problems: [], modified: "2026-11-09T10:00:00Z" }] }],
    stats: { lessons: 2, lessonsCompleted: 1, stepsWritten: 5, exercisesAnswered: 4, exercisesRightFirstTry: 3, predictions: 2 },
    cardsDue: 2, cards: 6, streakDays: 3, ...extra,
  });

  it("says where the operator stands and offers the lesson to go on with", async () => {
    const { navigate } = mount({ "GET /api/learning": () => ({ body: overview() }) }, null);

    expect(await screen.findByText("giorni di fila", { exact: false })).toBeTruthy();
    expect(screen.getByText("Riprendi da dove eri")).toBeTruthy();
    expect(screen.getByText(/5 passi scritti · 3 esercizi giusti al primo colpo/)).toBeTruthy();
    // The lesson in progress is the one offered; the finished one can be gone through again.
    fireEvent.click(screen.getByRole("button", { name: "Continua" }));
    expect(navigate).toHaveBeenCalledWith("learn", lessonRoute(5, PATH));
    expect(screen.getByRole("button", { name: /Lo scheletro.*Rivedi/s })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Ripassa" }));
    expect(navigate).toHaveBeenCalledWith("learn", "review");
  });

  it("with no lesson yet says how one is born, and offers no review", async () => {
    mount({ "GET /api/learning": () => ({ body: overview({ projects: [], cardsDue: 0, cards: 0, streakDays: 0,
      stats: { lessons: 0, lessonsCompleted: 0, stepsWritten: 0, exercisesAnswered: 0, exercisesRightFirstTry: 0, predictions: 0 } }) }) }, null);

    expect(await screen.findByText("Ancora nessuna lezione")).toBeTruthy();
    expect(screen.getByText("«Imparo»")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Ripassa" })).toBeNull();
  });
});

describe("Impara — one lesson, one act at a time", () => {
  it("asks for the prediction before the code, checks the step against the file, and gives an answer only for an answer", async () => {
    const state = { predicted: false, written: false, quiz: false };
    let checks = 0;
    const { calls } = mount({
      "GET /api/projects/5/lessons/view": () => ({ body: lesson(state) }),
      "POST /api/projects/5/lessons/prediction": () => { state.predicted = true;
        return { body: lesson(state).prediction }; },
      "POST /api/projects/5/lessons/step": (call) => {
        checks += 1;
        if (checks === 1) return { body: { index: 0, status: "DIFFERENT", detail: "In src/Prestito.java manca questa riga: }", done: false } };
        state.written = true;
        return { body: { index: 0, status: (call.body as { manual: boolean }).manual ? "MARKED" : "WRITTEN",
          detail: "Trovato in src/Prestito.java: il codice del passo è nel file.", done: true } };
      },
      "POST /api/projects/5/lessons/exercise": (call) => {
        const answer = (call.body as { answer?: string }).answer;
        if (answer !== "0") return { body: { index: 0, correct: false, done: false, attempts: 1, solution: null, explanation: null, keyPoints: [] } };
        state.quiz = true;
        return { body: { index: 0, correct: true, done: true, attempts: 2, solution: "0", explanation: "Qui è sempre vero.", keyPoints: [] } };
      },
    }, lessonRoute(5, PATH));

    // The idea first; what comes after it is closed until its turn.
    expect(await screen.findByText("In parole semplici")).toBeTruthy();
    expect(screen.getByText("Come in biblioteca.")).toBeTruthy();
    const rail = screen.getByRole("list", { name: "Le tappe della lezione" });
    expect((within(rail).getByRole("button", { name: /Passo 1/ }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByText(/public record Prestito/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Ho l'idea/ }));

    // The prediction: the expected answer is not on the page until the operator gave theirs.
    expect(await screen.findByText("Il prestito di ieri è scaduto?")).toBeTruthy();
    expect(screen.queryByText("Sì: oggi è dopo ieri.")).toBeNull();
    expect((within(rail).getByRole("button", { name: /Passo 1/ }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByPlaceholderText(/Scrivi che cosa ti aspetti/), { target: { value: "Secondo me sì" } });
    fireEvent.click(screen.getByRole("button", { name: "Questa è la mia previsione" }));
    expect(await screen.findByText("Sì: oggi è dopo ieri.")).toBeTruthy();
    expect(calls.find((c) => c.path === "/api/projects/5/lessons/prediction")!.body).toEqual({ path: PATH, answer: "Secondo me sì" });
    fireEvent.click(screen.getByRole("button", { name: /Vediamo il codice/ }));

    // The step: its file, its code, each line explained -- and the line an explanation speaks of lights up.
    expect(await screen.findByText("src/Prestito.java")).toBeTruthy();
    expect(screen.getByText("file nuovo: crealo e incolla", { exact: false })).toBeTruthy();
    const explained = screen.getByRole("button", { name: /dice se il prestito è scaduto/ });
    fireEvent.click(explained);
    const lit = document.querySelectorAll(".lesson-code li.is-lit");
    expect(lit).toHaveLength(1);
    expect(lit[0].textContent).toContain("boolean scaduto()");
    // Checked against the file: not there yet, and the check says which line.
    fireEvent.click(screen.getByRole("button", { name: "L'ho scritto nel file: controlla" }));
    expect(await screen.findByText(/manca questa riga/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Avanti/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "L'ho scritto nel file: controlla" }));
    expect(await screen.findByText(/il codice del passo è nel file/)).toBeTruthy();
    expect(calls.filter((c) => c.path === "/api/projects/5/lessons/step").map((c) => c.body))
      .toEqual([{ path: PATH, step: 0, manual: false }, { path: PATH, step: 0, manual: false }]);
    fireEvent.click(screen.getByRole("button", { name: /Avanti/ }));

    // The quiz: a wrong answer gets another try, not the solution.
    expect(await screen.findByText("Quiz")).toBeTruthy();
    fireEvent.click(screen.getByRole("radio", { name: "false" }));
    fireEvent.click(screen.getByRole("button", { name: "Controlla" }));
    expect(await screen.findByText(/Non ancora/)).toBeTruthy();
    expect(screen.queryByText("Qui è sempre vero.")).toBeNull();
    fireEvent.click(screen.getByRole("radio", { name: "true" }));
    fireEvent.click(screen.getByRole("button", { name: "Controlla" }));
    expect(await screen.findByText("Qui è sempre vero.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Avanti/ }));

    // The end: what the lesson leaves.
    expect(await screen.findByText("Lezione finita")).toBeTruthy();
    expect(screen.getByText("Record")).toBeTruthy();
    expect(screen.getByText(/la prima volta domani/)).toBeTruthy();
  });

  it("resumed where it was left, an act just done keeps its outcome on screen until the operator moves on", async () => {
    const state = { predicted: true, written: false, quiz: false };
    mount({
      "GET /api/projects/5/lessons/view": () => ({ body: lesson(state) }),
      "POST /api/projects/5/lessons/step": () => { state.written = true;
        return { body: { index: 0, status: "WRITTEN", detail: "Trovato in src/Prestito.java: il codice del passo è nel file.", done: true } }; },
    }, lessonRoute(5, PATH));

    // Straight to the step: the idea and the prediction are behind.
    fireEvent.click(await screen.findByRole("button", { name: "L'ho scritto nel file: controlla" }));

    // Done -- and still here, with what the check found; the quiz waits for "Avanti".
    expect(await screen.findByText(/il codice del passo è nel file/)).toBeTruthy();
    expect(screen.queryByText("Quiz")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Avanti/ }));
    expect(await screen.findByText("Quiz")).toBeTruthy();
  });

  it("in a narrow page the stops scroll sideways and the current one is brought into view", async () => {
    // jsdom has no layout: the rail is 300 px wide, its stops 900, the current one at 500.
    vi.spyOn(Element.prototype, "scrollWidth", "get").mockReturnValue(900);
    vi.spyOn(Element.prototype, "clientWidth", "get").mockReturnValue(300);
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
      const box = this.matches(".lesson-rail") ? { left: 0, width: 300 }
        : this.matches('[aria-current="step"]') ? { left: 500, width: 120 } : { left: 0, width: 0 };
      return { ...box, top: 0, height: 30, right: box.left + box.width, bottom: 30, x: box.left, y: 0, toJSON: () => box } as DOMRect;
    });
    try {
      mount({ "GET /api/projects/5/lessons/view": () => ({ body: lesson({ predicted: true }) }) }, lessonRoute(5, PATH));

      expect(await screen.findByRole("button", { name: "L'ho scritto nel file: controlla" })).toBeTruthy();
      const rail = screen.getByRole("list", { name: "Le tappe della lezione" });
      // Centred: 500 from the rail's edge, less half of what the rail shows beside the stop.
      await waitFor(() => expect(rail.scrollLeft).toBe(500 - (300 - 120) / 2));
    } finally {
      vi.restoreAllMocks();
    }
  });

  it("a lesson that departs from the format is shown with what it lacks", async () => {
    mount({ "GET /api/projects/5/lessons/view": () => ({ body: { ...lesson(),
      problems: ["Passo 1: manca la spiegazione «**Riga per riga**»."] } }) }, lessonRoute(5, PATH));

    expect(await screen.findByText(/non segue il formato in un punto/)).toBeTruthy();
    expect(screen.getByText("Passo 1: manca la spiegazione «**Riga per riga**».")).toBeTruthy();
  });
});

describe("Impara — the lesson's own markup", () => {
  it("shows `code`, **bold** and *emphasis* as such in the line-by-line, never the marks themselves", async () => {
    const view = lesson({ predicted: true });
    view.steps[0].lines = [{ code: "boolean scaduto()", text: "**una** riga sola, *non* due: `true` o `false`" }];
    mount({ "GET /api/projects/5/lessons/view": () => ({ body: view }) }, lessonRoute(5, PATH));

    const line = await screen.findByRole("button", { name: /riga sola/ });
    expect(line.querySelector("strong")?.textContent).toBe("una");
    expect(line.querySelector("em")?.textContent).toBe("non");
    expect([...line.querySelectorAll("span code")].map((c) => c.textContent)).toEqual(["true", "false"]);
    expect(line.textContent).not.toMatch(/[*`]/);
  });

  it("does the same in what a finished lesson leaves and on the back of a review card", async () => {
    mount({ "GET /api/projects/5/lessons/view": () => ({ body: { ...lesson({ predicted: true, written: true, quiz: true }),
      concepts: [{ name: "`Record`", text: "un tipo che **non** cambia: `final` in ogni campo" }] } }) }, lessonRoute(5, PATH));

    expect(await screen.findByText("Lezione finita")).toBeTruthy();
    const concepts = document.querySelector(".lesson-concepts")!;
    expect([...concepts.querySelectorAll("code")].map((c) => c.textContent)).toEqual(["Record", "final"]);
    expect(concepts.querySelector("strong")?.textContent).toBe("non");
    expect(concepts.textContent).not.toMatch(/[*`]/);
  });

  it("turns the back of a review card into code where it says so", async () => {
    const card = { id: 7, projectId: 5, projectName: "Biblioteca", lessonPath: PATH, name: "`Record`", box: 1, dueOn: "2026-11-10", reviews: 0 };
    mount({
      "GET /api/learning/review": () => ({ body: { due: [{ ...card, explanation: "" }], total: 1, nextDue: null } }),
      "GET /api/learning/review/7": () => ({ body: { ...card, explanation: "un tipo che non cambia: `final` in ogni campo" } }),
    }, "review");

    fireEvent.click(await screen.findByRole("button", { name: "Gira la carta" }));
    const answer = await screen.findByText(/in ogni campo/);
    expect(answer.querySelector("code")?.textContent).toBe("final");
    expect(screen.getByRole("heading", { name: "Record" }).querySelector("code")).toBeTruthy();
  });
});

describe("Impara — review", () => {
  it("shows the front of a card, turns it on request and records whether it was known", async () => {
    const { calls, navigate } = mount({
      "GET /api/learning/review": () => ({ body: { due: [
        { id: 7, projectId: 5, projectName: "Biblioteca", lessonPath: PATH, name: "Record", explanation: "", box: 1, dueOn: "2026-11-10", reviews: 0 }],
        total: 4, nextDue: "2026-11-12" } }),
      "GET /api/learning/review/7": () => ({ body: { id: 7, projectId: 5, projectName: "Biblioteca", lessonPath: PATH, name: "Record",
        explanation: "un tipo che non cambia dopo la creazione", box: 1, dueOn: "2026-11-10", reviews: 0 } }),
      "POST /api/learning/review/7": () => ({ body: { id: 7, projectId: 5, projectName: "Biblioteca", lessonPath: PATH, name: "Record",
        explanation: "un tipo che non cambia dopo la creazione", box: 2, dueOn: "2026-11-13", reviews: 1 } }),
    }, "review");

    expect(await screen.findByText("Record")).toBeTruthy();
    expect(screen.queryByText(/un tipo che non cambia/)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Gira la carta" }));
    expect(await screen.findByText("un tipo che non cambia dopo la creazione")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Lo sapevo/ }));

    await waitFor(() => expect(calls.some((c) => c.method === "POST")).toBe(true));
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ known: true });
    expect(await screen.findByText("Ripasso finito")).toBeTruthy();
    expect(screen.getByText(/1 di 1 le sapevi già/)).toBeTruthy();
    fireEvent.click(screen.getAllByRole("button", { name: /Torna a Impara/ })[0]);
    expect(navigate).toHaveBeenCalledWith("learn");
  });

  it("with nothing due says when the next cards come back", async () => {
    mount({ "GET /api/learning/review": () => ({ body: { due: [], total: 4, nextDue: "2026-11-12" } }) }, "review");

    expect(await screen.findByText("Niente da ripassare oggi")).toBeTruthy();
    expect(screen.getByText(/Le prossime tornano il 12 novembre/)).toBeTruthy();
  });
});
