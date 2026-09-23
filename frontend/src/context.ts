import { createContext, useContext } from "react";
import type { ControlPlane } from "./api/client";

export const ApiContext = createContext<ControlPlane | null>(null);

export function useApi(): ControlPlane {
  const api = useContext(ApiContext);
  if (!api) throw new Error("useApi outside a signed-in session");
  return api;
}
