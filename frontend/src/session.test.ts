import { beforeEach, describe, expect, it } from "vitest";
import { loadSession, saveSession } from "./session";

describe("what a tab keeps of its session (TD-53)", () => {
  beforeEach(() => sessionStorage.clear());

  it("keeps where the control plane is, who signed in and the anti-forgery token -- never a session token", () => {
    saveSession({ baseUrl: "http://localhost:8081", csrf: "csrf-abc", token: "aicos_s_secret",
      user: { id: 1, username: "admin" } as never });
    const stored = sessionStorage.getItem("aicos.session")!;
    expect(stored).not.toContain("aicos_s_secret");
    expect(loadSession()).toEqual({ baseUrl: "http://localhost:8081", csrf: "csrf-abc", user: { id: 1, username: "admin" } });
  });

  it("drops a session token left in storage by a console from before the cookie", () => {
    sessionStorage.setItem("aicos.session", JSON.stringify({ baseUrl: "http://localhost:8081", token: "aicos_s_old" }));
    expect(loadSession()).toBeNull();
    expect(sessionStorage.getItem("aicos.session")).toBeNull();
  });
});
