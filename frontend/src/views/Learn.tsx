import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { useApi } from "../context";
import type {
  ExerciseResult, LearningOverview, LessonExercise, LessonStep, LessonSummary, LessonView, ReviewCard, ReviewDeck, StepCheck,
} from "../api/types";
import { ProblemNote } from "../components/ui";
import { Icon } from "../components/icons";

type Navigate = (page: string, detail?: string) => void;

/** `learn/<projectId>~<lesson path>` opens a lesson; `learn/review` the cards due today. */
export function lessonRoute(projectId: number, path: string): string {
  return `${projectId}~${path}`;
}

/**
 * PHASE 37 (ADR-044): learning that is done, not read. The lessons the agents left in
 * the projects, taken one act at a time -- predict, copy the step into its file, be
 * checked, answer -- and the concepts of the finished ones coming back as cards.
 */
export function Learn({ selected, navigate }: { selected: string | null; navigate: Navigate }) {
  const lesson = selected && /^\d+~/.test(selected)
    ? { projectId: Number(selected.slice(0, selected.indexOf("~"))), path: selected.slice(selected.indexOf("~") + 1) }
    : null;
  if (lesson) {
    return <LessonPlayer key={selected} projectId={lesson.projectId} path={lesson.path} onBack={() => navigate("learn")} />;
  }
  if (selected === "review") {
    return <ReviewSession onBack={() => navigate("learn")} />;
  }
  return <LearnHome navigate={navigate} />;
}

// --- the page ------------------------------------------------------------------------

function LearnHome({ navigate }: { navigate: Navigate }) {
  const api = useApi();
  const [overview, setOverview] = useState<LearningOverview | null>(null);
  const [problem, setProblem] = useState<unknown>(null);

  useEffect(() => { api.learningOverview().then(setOverview).catch(setProblem); }, [api]);

  const open = overview?.projects.flatMap((p) => p.lessons.filter((l) => !l.completed).map((l) => ({ project: p, lesson: l }))) ?? [];
  const next = open[0];

  return (
    <div className="stack learn">
      <div className="page-head">
        <div><h1>Impara</h1>
          <p>Capisci davvero quello che stai costruendo: un passo alla volta, con le mani sul codice.</p></div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {overview === null && !problem && <p className="muted">Carico le lezioni…</p>}
      {overview && (
        <>
          <section className="card learn-strip" aria-label="Il tuo percorso">
            <div className={overview.streakDays > 0 ? "learn-figure is-lit" : "learn-figure"}>
              <span className="widget-icon"><Icon name="flame" size={17} /></span>
              <div className="learn-figure-text">
                <p><strong>{overview.streakDays}</strong> {overview.streakDays === 1 ? "giorno di fila" : "giorni di fila"}</p>
                <small>{overview.streakDays > 0 ? "Un passo anche oggi e la serie continua." : "Comincia oggi: basta un passo."}</small>
              </div>
            </div>
            <div className="learn-figure">
              <span className="widget-icon"><Icon name="learn" size={17} /></span>
              <div className="learn-figure-text">
                <p><strong>{overview.stats.lessonsCompleted}</strong> di {overview.stats.lessons} lezioni finite</p>
                <small>{count(overview.stats.stepsWritten, "passo scritto", "passi scritti")} · {count(overview.stats.exercisesRightFirstTry,
                  "esercizio giusto al primo colpo", "esercizi giusti al primo colpo")}</small>
              </div>
            </div>
            <div className="learn-figure">
              <span className="widget-icon"><Icon name="cards" size={17} /></span>
              <div className="learn-figure-text">
                <p><strong>{overview.cardsDue}</strong> {overview.cardsDue === 1 ? "carta da ripassare" : "carte da ripassare"}</p>
                <small>{overview.cards === 0 ? "Le carte arrivano quando finisci una lezione."
                  : `${overview.cards} concetti in tutto, che tornano a distanza di giorni.`}</small>
              </div>
              {overview.cardsDue > 0 && (
                <button className="btn btn-primary btn-small" onClick={() => navigate("learn", "review")}>Ripassa</button>
              )}
            </div>
          </section>

          {next && (
            <section className="card learn-next">
              <div className="learn-next-text">
                <span className="learn-eyebrow">{next.lesson.done > 0 ? "Riprendi da dove eri" : "Comincia da qui"}</span>
                <h2>{next.lesson.title}</h2>
                <p className="muted">{next.project.projectName}{next.lesson.task ? ` · ${next.lesson.task}` : ""}</p>
                <LessonProgress lesson={next.lesson} />
              </div>
              <button className="btn btn-primary" onClick={() => navigate("learn", lessonRoute(next.project.projectId, next.lesson.path))}>
                <Icon name="play" size={14} /> {next.lesson.done > 0 ? "Continua" : "Inizia"}
              </button>
            </section>
          )}

          {overview.projects.length === 0 && (
            <section className="card card-body learn-empty">
              <h2>Ancora nessuna lezione</h2>
              <p>Una lezione nasce quando consegni una task a un agente e scegli <strong>«Imparo»</strong>: l'agente scrive il
                codice e lo spiega riga per riga, tu lo porti nei file e lo capisci. Se non puoi seguire, scegli{" "}
                <strong>«Non posso seguire ora»</strong>: lavora da solo e ti lascia la lezione da ripercorrere dopo.</p>
              <p className="muted">Le lezioni stanno nella cartella <code>lessons/</code> di ogni progetto e compaiono qui da sole.</p>
              <div className="row">
                <button className="btn btn-primary" onClick={() => navigate("tasks")}>Vai alle task</button>
                <button className="btn" onClick={() => navigate("projects")}>Vai ai progetti</button>
              </div>
            </section>
          )}

          {overview.projects.map((project) => {
            const finished = project.lessons.filter((l) => l.completed).length;
            return (
              <section key={project.projectId} className="card learn-project">
                <div className="card-head">
                  <div className="learn-project-title">
                    <h2>{project.projectName}</h2>
                    <small>{finished} di {project.lessons.length} {project.lessons.length === 1 ? "lezione finita" : "lezioni finite"}</small>
                  </div>
                  <button className="btn btn-small" onClick={() => navigate("projects", String(project.projectId))}>Apri il progetto</button>
                </div>
                <ul className="lesson-list">
                  {project.lessons.map((lesson) => (
                    <li key={lesson.path}>
                      <button className={lesson.completed ? "lesson-row is-done" : "lesson-row"} title={lesson.title}
                              onClick={() => navigate("learn", lessonRoute(project.projectId, lesson.path))}>
                        <span className={lesson.completed ? "lesson-mark is-done" : "lesson-mark"} aria-hidden="true">
                          {lesson.completed && <Icon name="check" size={13} />}
                        </span>
                        <span className="lesson-row-text">
                          <strong>{lesson.title}</strong>
                          <small className="lesson-row-meta">
                            <span>{lesson.task ?? lesson.path}</span>
                            {lesson.problems.length > 0 && (
                              <span className="badge badge-warn" title="La lezione non segue il formato in qualche punto">fuori formato</span>
                            )}
                          </small>
                        </span>
                        <LessonProgress lesson={lesson} />
                        <span className="lesson-row-cta">
                          {lesson.completed ? "Rivedi" : lesson.done > 0 ? "Continua" : "Inizia"}<Icon name="chevron" size={14} />
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            );
          })}
        </>
      )}
    </div>
  );
}

/** «1 passo scritto», «3 passi scritti». */
function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

function LessonProgress({ lesson }: { lesson: LessonSummary }) {
  const pct = lesson.items ? Math.round((lesson.done / lesson.items) * 100) : 0;
  return (
    <span className="lesson-progress" title={`${lesson.done} di ${lesson.items}`}>
      <span className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={lesson.items} aria-valuenow={lesson.done}
            aria-label={`${lesson.done} di ${lesson.items} fatti`}><span style={{ width: `${pct}%` }} /></span>
      <small className="muted">{lesson.done}/{lesson.items}</small>
    </span>
  );
}

// --- review --------------------------------------------------------------------------

function ReviewSession({ onBack }: { onBack: () => void }) {
  const api = useApi();
  const [deck, setDeck] = useState<ReviewDeck | null>(null);
  const [at, setAt] = useState(0);
  const [back, setBack] = useState<ReviewCard | null>(null);
  const [known, setKnown] = useState(0);
  const [problem, setProblem] = useState<unknown>(null);

  useEffect(() => { api.reviewDeck().then(setDeck).catch(setProblem); }, [api]);

  const card = deck?.due[at];
  const turn = () => { if (card) api.turnCard(card.id).then(setBack).catch(setProblem); };
  const answer = (knew: boolean) => {
    if (!card) return;
    api.reviewCard(card.id, knew).then(() => {
      setKnown((n) => n + (knew ? 1 : 0));
      setBack(null);
      setAt((n) => n + 1);
    }).catch(setProblem);
  };

  return (
    <div className="stack learn review">
      <BackRow onBack={onBack} />
      <div className="page-head">
        <div><h1>Ripasso</h1><p>Prima prova a dirlo a voce, poi gira la carta.</p></div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {deck && deck.due.length === 0 && (
        <div className="card card-body learn-empty">
          <h2>Niente da ripassare oggi</h2>
          <p className="muted">{deck.total === 0 ? "Le carte arrivano quando finisci una lezione."
            : deck.nextDue ? `Le prossime tornano il ${new Date(deck.nextDue).toLocaleDateString("it-IT", { day: "numeric", month: "long" })}.`
            : "Tutte le carte sono a posto."}</p>
        </div>
      )}
      {deck && card && (
        <div className="review-card card" aria-live="polite">
          <small className="muted">Carta {at + 1} di {deck.due.length} · {card.projectName}</small>
          <h2>{inline(card.name)}</h2>
          {back === null ? (
            <>
              <p className="muted">Che cos'è? A che cosa serve? Dillo con parole tue.</p>
              <button className="btn btn-primary" onClick={turn}>Gira la carta</button>
            </>
          ) : (
            <>
              <p className="review-answer">{inline(back.explanation)}</p>
              <div className="row">
                <button className="btn btn-primary" onClick={() => answer(true)}><Icon name="check" size={14} /> Lo sapevo</button>
                <button className="btn" onClick={() => answer(false)}>Non ancora</button>
              </div>
              <small className="muted">«Lo sapevo» la fa tornare più in là; «Non ancora» la riporta domani.</small>
            </>
          )}
        </div>
      )}
      {deck && deck.due.length > 0 && !card && (
        <div className="card card-body learn-empty" role="status">
          <Burst />
          <h2>Ripasso finito</h2>
          <p>{known} di {deck.due.length} le sapevi già. Le altre tornano domani: è così che restano.</p>
          <button className="btn btn-primary" onClick={onBack}>Torna a Impara</button>
        </div>
      )}
    </div>
  );
}

// --- one lesson ----------------------------------------------------------------------

type Stage =
  | { kind: "intro" }
  | { kind: "prediction" }
  | { kind: "step"; index: number }
  | { kind: "exercise"; index: number }
  | { kind: "done" };

function stagesOf(lesson: LessonView): Stage[] {
  return [
    { kind: "intro" },
    ...(lesson.prediction ? [{ kind: "prediction" } as Stage] : []),
    ...lesson.steps.map((s) => ({ kind: "step", index: s.index }) as Stage),
    ...lesson.exercises.map((e) => ({ kind: "exercise", index: e.index }) as Stage),
    { kind: "done" },
  ];
}

function stageDone(stage: Stage, lesson: LessonView, started: boolean): boolean {
  switch (stage.kind) {
    case "intro": return started || lesson.done > 0;
    case "prediction": return lesson.prediction?.answered ?? true;
    case "step": return lesson.steps[stage.index].done;
    case "exercise": return lesson.exercises[stage.index].done;
    case "done": return false;
  }
}

/** The first stage not done: as far as the lesson goes for now. */
function frontierOf(lesson: LessonView, started: boolean): number {
  const stages = stagesOf(lesson);
  const first = stages.findIndex((s) => !stageDone(s, lesson, started));
  return first < 0 ? stages.length - 1 : first;
}

function stageLabel(stage: Stage, lesson: LessonView): string {
  switch (stage.kind) {
    case "intro": return "L'idea";
    case "prediction": return "Prova a prevedere";
    case "step": return `Passo ${stage.index + 1} — ${lesson.steps[stage.index].title}`;
    case "exercise": return `Esercizio ${stage.index + 1}`;
    case "done": return "Cosa hai imparato";
  }
}

function LessonPlayer({ projectId, path, onBack }: { projectId: number; path: string; onBack: () => void }) {
  const api = useApi();
  const [lesson, setLesson] = useState<LessonView | null>(null);
  const [started, setStarted] = useState(false);
  const [at, setAt] = useState<number | null>(null);
  const [problem, setProblem] = useState<unknown>(null);

  const reload = useCallback(() => api.lesson(projectId, path).then((l) => { setLesson(l); return l; }), [api, projectId, path]);
  // Opened, the lesson is where the operator left it -- and stays on that stage: an act just done keeps its
  // outcome on screen (the explanation of an exercise is the lesson) until the operator moves on.
  useEffect(() => {
    reload().then((l) => setAt((chosen) => chosen ?? frontierOf(l, false))).catch(setProblem);
  }, [reload]);

  const stages = useMemo(() => (lesson ? stagesOf(lesson) : []), [lesson]);
  const isDone = useCallback((stage: Stage): boolean => (lesson ? stageDone(stage, lesson, started) : false), [lesson, started]);
  // One act at a time: the first stage not done is as far as the lesson goes.
  const frontier = useMemo(() => (lesson ? frontierOf(lesson, started) : 0), [lesson, started]);
  const current = Math.min(at ?? frontier, frontier);
  const stage = stages[current];
  const forward = () => setAt(Math.min(current + 1, stages.length - 1));

  // In a narrow page the stops are a row that scrolls sideways: the current one is brought to its middle,
  // scrolling the row and never the page. Beside the stage, the rail shows them all and has nothing to scroll.
  const rail = useRef<HTMLOListElement>(null);
  useEffect(() => {
    const list = rail.current;
    const stop = list?.querySelector<HTMLElement>('[aria-current="step"]');
    if (!list || !stop || list.scrollWidth <= list.clientWidth) return;
    const box = list.getBoundingClientRect(), at = stop.getBoundingClientRect();
    list.scrollLeft += at.left - box.left - (box.width - at.width) / 2;
  }, [current, lesson]);

  if (!lesson || !stage) {
    return (
      <div className="stack learn">
        <BackRow onBack={onBack} />
        <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
        {!problem && <p className="muted">Apro la lezione…</p>}
      </div>
    );
  }

  return (
    <div className="stack learn">
      <BackRow onBack={onBack} />
      <div className="page-head">
        <div>
          <h1>{lesson.title}</h1>
          <p>{lesson.task ? `${lesson.task} · ` : ""}{lesson.done} di {lesson.items} fatti</p>
        </div>
      </div>
      <ProblemNote problem={problem} onDismiss={() => setProblem(null)} />
      {lesson.problems.length > 0 && (
        <details className="notice notice-warn">
          <summary>Questa lezione non segue il formato in {lesson.problems.length === 1 ? "un punto" : `${lesson.problems.length} punti`}: quello che manca non compare.</summary>
          <ul>{lesson.problems.map((p) => <li key={p}>{p}</li>)}</ul>
        </details>
      )}
      <div className="lesson">
        <ol className="lesson-rail" aria-label="Le tappe della lezione" ref={rail}>
          {stages.map((s, i) => (
            <li key={i}>
              <button className="lesson-stop" disabled={i > frontier} aria-current={i === current ? "step" : undefined}
                      data-done={isDone(s) || undefined} title={stageLabel(s, lesson)} onClick={() => setAt(i)}>
                <span className="lesson-dot" aria-hidden="true">{isDone(s) && <Icon name="check" size={11} />}</span>
                <span>{stageLabel(s, lesson)}</span>
              </button>
            </li>
          ))}
        </ol>
        <div className="lesson-stage card" key={current}>
          {stage.kind === "intro" && (
            <>
              <h2>In parole semplici</h2>
              <Prose text={lesson.intro || "Questa lezione non ha un'introduzione."} />
              <button className="btn btn-primary" onClick={() => { setStarted(true); setAt(current + 1); }}>
                Ho l'idea: andiamo <Icon name="chevron" size={14} />
              </button>
            </>
          )}
          {stage.kind === "prediction" && lesson.prediction && (
            <PredictionStage prediction={lesson.prediction} onNext={forward}
                             onAnswer={(answer) => api.predict(projectId, path, answer).then(() => reload()).then(() => undefined)}
                             onProblem={setProblem} />
          )}
          {stage.kind === "step" && (
            <StepStage step={lesson.steps[stage.index]} onNext={forward} onProblem={setProblem}
                       onCheck={(manual) => api.checkStep(projectId, path, stage.index, manual)
                         .then((check) => (check.done ? reload().then(() => check) : check))} />
          )}
          {stage.kind === "exercise" && (
            <ExerciseStage exercise={lesson.exercises[stage.index]} onNext={forward} onProblem={setProblem}
                           onAnswer={(answer, reveal) => api.answerExercise(projectId, path, stage.index, answer, reveal)
                             .then((result) => (result.done ? reload().then(() => result) : result))} />
          )}
          {stage.kind === "done" && (
            lesson.completed ? (
              <div role="status">
                <Burst />
                <h2>Lezione finita</h2>
                <p>Hai previsto, scritto e spiegato. Questo è quello che ti porti via:</p>
                {lesson.concepts.length > 0 ? (
                  <dl className="lesson-concepts">
                    {lesson.concepts.map((c) => <div key={c.name}><dt>{inline(c.name)}</dt><dd>{inline(c.text)}</dd></div>)}
                  </dl>
                ) : <p className="muted">La lezione non elenca concetti da ripassare.</p>}
                {lesson.concepts.length > 0 && <p className="muted">Tornano come carte da ripassare: la prima volta domani.</p>}
                <button className="btn btn-primary" onClick={onBack}>Torna a Impara</button>
              </div>
            ) : <p className="muted">Finisci le tappe qui a fianco: questa si apre alla fine.</p>
          )}
        </div>
      </div>
    </div>
  );
}

function Prose({ text }: { text: string }) {
  return <div className="lesson-prose">{text.split(/\n{2,}/).map((p, i) => <p key={i}>{inline(p)}</p>)}</div>;
}

/**
 * `code` in backticks, **bold** and *emphasis* inside a sentence -- what the lessons the agents write use
 * between the lines; nothing else of Markdown is interpreted, and nothing inside `code` is.
 */
function inline(text: string): ReactNode[] {
  return text.split(/(`[^`]+`)/).flatMap((part, i) =>
    part.startsWith("`") && part.endsWith("`") && part.length > 2 ? [<code key={i}>{part.slice(1, -1)}</code>] : emphasis(part, i));
}

/** `**bold**` and `*emphasis*`: a single star needs a word right inside it, so `2 * 3` stays as it is. */
function emphasis(text: string, at: number): ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*|\*[^*\s](?:[^*]*[^*\s])?\*)/).map((part, i) => {
    if (part.length > 4 && part.startsWith("**") && part.endsWith("**")) return <strong key={`${at}.${i}`}>{part.slice(2, -2)}</strong>;
    if (part.length > 2 && part.startsWith("*") && part.endsWith("*")) return <em key={`${at}.${i}`}>{part.slice(1, -1)}</em>;
    return part;
  });
}

/** Back to the page, above the heading: as a project's page goes back to the projects. */
function BackRow({ onBack }: { onBack: () => void }) {
  return (
    <div className="row">
      <button className="btn btn-small" onClick={onBack}><Icon name="back" size={14} /> Impara</button>
    </div>
  );
}

function PredictionStage({ prediction, onAnswer, onNext, onProblem }: {
  prediction: NonNullable<LessonView["prediction"]>; onAnswer: (answer: string) => Promise<void>; onNext: () => void;
  onProblem: (problem: unknown) => void;
}) {
  const [answer, setAnswer] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <>
      <h2>Prova a prevedere</h2>
      <p className="muted">Prima di vedere il codice. Sbagliare qui è il modo più rapido di capire.</p>
      <p className="lesson-question">{inline(prediction.question)}</p>
      {prediction.answered ? (
        <>
          <div className="lesson-compare">
            <div><small className="muted">La tua previsione</small><p>{prediction.yourAnswer}</p></div>
            <div><small className="muted">Com'è davvero</small><p>{inline(prediction.answer ?? "")}</p></div>
          </div>
          <button className="btn btn-primary" onClick={onNext}>Vediamo il codice <Icon name="chevron" size={14} /></button>
        </>
      ) : (
        <form className="stack" onSubmit={(e) => {
          e.preventDefault();
          setBusy(true);
          onAnswer(answer).catch(onProblem).finally(() => setBusy(false));
        }}>
          <label className="field"><span className="sr-only">La tua previsione</span>
            <textarea value={answer} onChange={(e) => setAnswer(e.target.value)} rows={3} maxLength={2000}
                      placeholder="Scrivi che cosa ti aspetti, anche se non sei sicuro" /></label>
          <div><button className="btn btn-primary" disabled={busy || answer.trim().length === 0}>Questa è la mia previsione</button></div>
        </form>
      )}
    </>
  );
}

const MODE_LABEL: Record<string, string> = {
  NEW: "file nuovo: crealo e incolla", EDIT: "file che c'è già: cambia questo pezzo", DONE: "già scritto dall'agente: leggilo nel file",
};

function StepStage({ step, onCheck, onNext, onProblem }: {
  step: LessonStep; onCheck: (manual: boolean) => Promise<StepCheck>; onNext: () => void; onProblem: (problem: unknown) => void;
}) {
  const [lit, setLit] = useState<number | null>(null);
  const [check, setCheck] = useState<StepCheck | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const codeLines = useMemo(() => step.code.split("\n"), [step.code]);
  const highlighted = useMemo(() => {
    const fragment = lit === null ? null : step.lines[lit]?.code;
    if (!fragment) return new Set<number>();
    return new Set(codeLines.map((line, i) => (line.includes(fragment) ? i : -1)).filter((i) => i >= 0));
  }, [lit, step.lines, codeLines]);
  const run = (manual: boolean) => {
    setBusy(true);
    onCheck(manual).then(setCheck).catch(onProblem).finally(() => setBusy(false));
  };
  const copy = () => {
    navigator.clipboard?.writeText(step.code).then(() => { setCopied(true); window.setTimeout(() => setCopied(false), 1800); })
      .catch(() => undefined);
  };
  return (
    <>
      <h2>{step.title}</h2>
      <div className="lesson-file">
        <Icon name="file" size={15} />
        <div className="lesson-file-text">
          {step.file ? <code>{step.file}</code> : <span className="muted">la lezione non indica il file</span>}
          <small>{MODE_LABEL[step.mode] ?? step.mode}</small>
        </div>
        <button className="btn btn-small" onClick={copy}><Icon name="copy" size={13} /> {copied ? "Copiato" : "Copia il codice"}</button>
      </div>
      <CodeBlock lines={codeLines} lit={highlighted} />
      {step.lines.length > 0 && (
        <>
          <h3>Riga per riga</h3>
          <ul className="lesson-lines">
            {step.lines.map((line, i) => (
              <li key={i}>
                {line.code ? (
                  <button className="lesson-line" aria-pressed={lit === i} onClick={() => setLit(lit === i ? null : i)}
                          onMouseEnter={() => setLit(i)} onFocus={() => setLit(i)}>
                    <code>{line.code}</code><span>{inline(line.text)}</span>
                  </button>
                ) : <p className="lesson-line is-note">{inline(line.text)}</p>}
              </li>
            ))}
          </ul>
        </>
      )}
      {step.why && <p className="lesson-why"><strong>Perché così.</strong> {inline(step.why)}</p>}
      {step.done ? (
        <div className="lesson-result is-ok" role="status"><Icon name="check" size={15} />
          <span>{check?.detail ?? "Fatto: il codice del passo è nel file."}</span>
          <span className="spacer" /><button className="btn btn-primary" onClick={onNext}>Avanti <Icon name="chevron" size={14} /></button></div>
      ) : (
        <>
          {check && !check.done && (
            <div className="lesson-result is-miss" role="status"><Icon name="warning" size={15} /><span>{check.detail}</span></div>
          )}
          <div className="row">
            <button className="btn btn-primary" disabled={busy} onClick={() => run(false)}>
              {step.mode === "DONE" ? "L'ho letto nel file: controlla" : "L'ho scritto nel file: controlla"}
            </button>
            <button className="btn" disabled={busy} onClick={() => run(true)}
                    title="Per un passo che hai adattato a modo tuo, o senza file">Segna come fatto</button>
          </div>
        </>
      )}
    </>
  );
}

function CodeBlock({ lines, lit, picked, onPick }: {
  lines: string[]; lit?: Set<number>; picked?: number | null; onPick?: (line: number) => void;
}) {
  return (
    <ol className={onPick ? "lesson-code is-pickable" : "lesson-code"}>
      {lines.map((line, i) => (
        <li key={i} className={lit?.has(i) || picked === i ? "is-lit" : undefined}>
          {onPick
            ? <button aria-pressed={picked === i} aria-label={`Riga ${i + 1}`} onClick={() => onPick(i)}><code>{line || " "}</code></button>
            : <code>{line || " "}</code>}
        </li>
      ))}
    </ol>
  );
}

const KIND_TITLE: Record<string, string> = {
  QUIZ: "Quiz", FILL: "Completa", BUG: "Trova l'errore", EXPLAIN: "Spiega a parole tue",
};

function ExerciseStage({ exercise, onAnswer, onNext, onProblem }: {
  exercise: LessonExercise; onAnswer: (answer: string | null, reveal: boolean) => Promise<ExerciseResult>; onNext: () => void;
  onProblem: (problem: unknown) => void;
}) {
  const [choice, setChoice] = useState<number | null>(null);
  const [text, setText] = useState("");
  const [result, setResult] = useState<ExerciseResult | null>(null);
  const [busy, setBusy] = useState(false);
  const done = exercise.done || (result?.done ?? false);
  const solution = result?.solution ?? exercise.solution;
  const explanation = result?.explanation ?? exercise.explanation;
  const keyPoints = result?.keyPoints.length ? result.keyPoints : exercise.keyPoints;
  const send = (answer: string | null, reveal = false) => {
    setBusy(true);
    onAnswer(answer, reveal).then(setResult).catch(onProblem).finally(() => setBusy(false));
  };
  const given = exercise.kind === "QUIZ" || exercise.kind === "BUG"
    ? (choice === null ? null : String(exercise.kind === "BUG" ? choice + 1 : choice))
    : (text.trim() ? text.trim() : null);
  const codeLines = exercise.code ? exercise.code.split("\n") : [];

  return (
    <>
      <h2>{KIND_TITLE[exercise.kind] ?? exercise.kind}</h2>
      <p className="lesson-question">{inline(exercise.prompt)}</p>

      {exercise.kind === "QUIZ" && (
        <div className="lesson-options" role="radiogroup" aria-label="Risposte">
          {exercise.options.map((option, i) => (
            <label key={i} className="lesson-option" data-right={done && solution === String(i) || undefined}
                   data-wrong={result && !result.correct && choice === i && !done || undefined}>
              <input type="radio" name="quiz" checked={choice === i} disabled={done} onChange={() => { setChoice(i); setResult(null); }} />
              <span>{inline(option)}</span>
            </label>
          ))}
        </div>
      )}
      {exercise.kind === "FILL" && (
        <>
          <CodeBlock lines={codeLines} />
          <label className="field"><span>Che cosa va al posto di <code>___</code>?</span>
            <input className="mono" value={text} disabled={done} onChange={(e) => { setText(e.target.value); setResult(null); }}
                   autoComplete="off" spellCheck={false} /></label>
        </>
      )}
      {exercise.kind === "BUG" && (
        <>
          <p className="muted">Tocca la riga sbagliata.</p>
          <CodeBlock lines={codeLines} picked={done && solution ? Number(solution) - 1 : choice}
                     onPick={done ? undefined : (line) => { setChoice(line); setResult(null); }} />
        </>
      )}
      {exercise.kind === "EXPLAIN" && (
        <label className="field"><span className="sr-only">La tua spiegazione</span>
          <textarea value={done ? (exercise.yourAnswer ?? text) : text} disabled={done} rows={4} maxLength={2000}
                    onChange={(e) => setText(e.target.value)} placeholder="Come lo diresti a un amico che non programma?" /></label>
      )}

      {done ? (
        <div className={result?.correct === false || exercise.firstTryRight === false ? "lesson-result" : "lesson-result is-ok"} role="status">
          <Icon name={exercise.kind === "EXPLAIN" ? "sparkle" : "check"} size={15} />
          <div>
            {exercise.kind === "EXPLAIN"
              ? <><strong>Confronta con i punti chiave.</strong> Quali hai toccato?
                <ul className="lesson-points">{keyPoints.map((p) => <li key={p}><label><input type="checkbox" /> {inline(p)}</label></li>)}</ul></>
              : <>
                {exercise.kind === "FILL" && solution && <p>La soluzione: <code>{solution}</code></p>}
                {exercise.kind === "BUG" && solution && <p>L'errore è alla riga {solution}.</p>}
                {explanation && <p>{inline(explanation)}</p>}
              </>}
          </div>
          <span className="spacer" /><button className="btn btn-primary" onClick={onNext}>Avanti <Icon name="chevron" size={14} /></button>
        </div>
      ) : (
        <>
          {result && result.correct === false && (
            <div className="lesson-result is-miss" role="status"><Icon name="warning" size={15} />
              <span>Non ancora. Rileggi il passo e riprova: il tentativo conta, non il voto.</span></div>
          )}
          <div className="row">
            <button className="btn btn-primary" disabled={busy || given === null} onClick={() => send(given)}>
              {exercise.kind === "EXPLAIN" ? "Questa è la mia spiegazione" : "Controlla"}</button>
            {exercise.kind !== "EXPLAIN" && (
              <button className="btn" disabled={busy} onClick={() => send(null, true)}>Mostrami la soluzione</button>
            )}
          </div>
        </>
      )}
    </>
  );
}

/** The one moment of celebration: a lesson or a review finished. Still, when the system asks for less motion. */
function Burst() {
  return (
    <span className="burst" aria-hidden="true">
      <Icon name="check" size={22} />
      {Array.from({ length: 8 }, (_, i) => <i key={i} style={{ "--i": i } as CSSProperties} />)}
    </span>
  );
}
