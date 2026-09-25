import { createContext, useContext } from "react";
import type { ControlPlane, Session } from "./api/client";
import { displayName } from "./preferences";

export const ApiContext = createContext<ControlPlane | null>(null);

export function useApi(): ControlPlane {
  const api = useContext(ApiContext);
  if (!api) throw new Error("useApi outside a signed-in session");
  return api;
}

/** Who is signed in (ADR-024): the console hides what the role cannot do, the server refuses it anyway. */
export const SessionContext = createContext<Session | null>(null);

export function useIsAdmin(): boolean {
  const session = useContext(SessionContext);
  return session?.user?.role === "ADMIN";
}

/** The name the console greets and shows: the account's own, else its username, else this browser's preference. */
export function useDisplayName(): string {
  const user = useContext(SessionContext)?.user;
  return user?.displayName || user?.username || displayName();
}
