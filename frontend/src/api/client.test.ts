import { describe, expect, it } from "vitest";
import { ApiProblem, ControlPlane } from "./client";
import { fakeFetch } from "../test/fakeServer";

const session = { baseUrl: "http://localhost:8080/", token: "operator-token-0123456789" };
const TASK = { id: 7, title: "Plan", description: null, status: "OPEN", priority: "HIGH", projectId: null, agentId: 1 };

describe("ControlPlane", () => {
  it("sends the bearer token on every request (ADR-013)", async () => {
    const { fetcher, calls } = fakeFetch({ "GET /api/tasks": () => ({ body: [] }) });
    await new ControlPlane(session, fetcher).tasks();
    expect(calls[0].headers.Authorization).toBe("Bearer operator-token-0123456789");
    expect(calls[0].path).toBe("/api/tasks");
  });

  it("returns the ETag of a single resource, and sends it back as If-Match (ADR-009)", async () => {
    const { fetcher, calls } = fakeFetch({
      "GET /api/tasks/7": () => ({ body: TASK, etag: '"3"' }),
      "POST /api/tasks/7/start": () => ({ body: { ...TASK, status: "IN_PROGRESS" }, etag: '"4"' }),
    });
    const api = new ControlPlane(session, fetcher);

    const read = await api.task(7);
    expect(read.etag).toBe('"3"');

    const started = await api.transition(7, "start", read.etag);
    expect(calls[1].method).toBe("POST");
    expect(calls[1].headers["If-Match"]).toBe('"3"');
    expect(started).toEqual({ body: { ...TASK, status: "IN_PROGRESS" }, etag: '"4"' });
  });

  it("launches a run with the task's tag and the chosen model", async () => {
    const { fetcher, calls } = fakeFetch({ "POST /api/tasks/7/runs": () => ({ status: 202, body: { id: 1, status: "QUEUED" } }) });
    await new ControlPlane(session, fetcher).launchRun(7, '"4"', "ollama:llama3.2:3b");
    expect(calls[0].headers["If-Match"]).toBe('"4"');
    expect(calls[0].body).toEqual({ model: "ollama:llama3.2:3b" });
  });

  it("turns a problem detail into an ApiProblem that keeps its stable type (ADR-007)", async () => {
    const { fetcher } = fakeFetch({
      "POST /api/tasks/7/start": () => ({
        status: 412, problem: true,
        body: { type: "urn:ai-company-os:problem:precondition-failed", title: "Precondition failed", status: 412,
          detail: "The resource changed since the ETag you supplied" },
      }),
    });
    const failure = await new ControlPlane(session, fetcher).transition(7, "start", '"2"').catch((e) => e);
    expect(failure).toBeInstanceOf(ApiProblem);
    expect(failure.slug).toBe("precondition-failed");
    expect(failure.isStale).toBe(true);
  });

  it("keeps the field errors of a validation failure", async () => {
    const { fetcher } = fakeFetch({
      "POST /api/tasks": () => ({
        status: 400, problem: true,
        body: { type: "urn:ai-company-os:problem:validation-failed", title: "Invalid request payload", status: 400,
          errors: { priority: "priority must be one of LOW, MEDIUM, HIGH" } },
      }),
    });
    const failure = await new ControlPlane(session, fetcher)
      .createTask({ title: "t", status: "OPEN", priority: "URGENT" }).catch((e) => e);
    expect(failure.errors.priority).toContain("LOW, MEDIUM, HIGH");
  });

  it("refuses a single read that comes back without an ETag, instead of failing later with a 428", async () => {
    const { fetcher } = fakeFetch({ "GET /api/tasks/7": () => ({ body: TASK }) });
    const failure = await new ControlPlane(session, fetcher).task(7).catch((e) => e);
    expect(failure).toBeInstanceOf(ApiProblem);
    expect(failure.slug).toBe("missing-etag");
  });

  it("says plainly when nothing answers", async () => {
    const failing = (async () => { throw new TypeError("Failed to fetch"); }) as unknown as typeof fetch;
    const failure = await new ControlPlane(session, failing).tasks().catch((e) => e);
    expect(failure.slug).toBe("control-plane-unreachable");
  });
});
