import { vi } from "vitest";

export interface Call {
  method: string;
  path: string;
  headers: Record<string, string>;
  body: unknown;
}

type Handler = (call: Call) => { status?: number; body?: unknown; etag?: string; problem?: boolean };

/** A fetch that records every call and answers from a route table: no network. */
export function fakeFetch(routes: Record<string, Handler>) {
  const calls: Call[] = [];
  const fetcher = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input));
    const call: Call = {
      method: init?.method ?? "GET",
      path: url.pathname + url.search,
      headers: Object.fromEntries(Object.entries((init?.headers as Record<string, string>) ?? {})),
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    };
    calls.push(call);
    const handler = routes[`${call.method} ${url.pathname}`] ?? routes[`${call.method} ${call.path}`];
    if (!handler) {
      return new Response(JSON.stringify({ type: "urn:ai-company-os:problem:resource-not-found", title: "Not found", status: 404 }),
        { status: 404, headers: { "Content-Type": "application/problem+json" } });
    }
    const answer = handler(call);
    const headers: Record<string, string> = {
      "Content-Type": answer.problem ? "application/problem+json" : "application/json",
    };
    if (answer.etag) headers.ETag = answer.etag;
    return new Response(answer.body === undefined ? "" : JSON.stringify(answer.body), { status: answer.status ?? 200, headers });
  });
  return { fetcher: fetcher as unknown as typeof fetch, calls };
}
