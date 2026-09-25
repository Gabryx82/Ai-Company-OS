import type { AutonomyLevel } from "../api/types";
import type { IconName } from "../components/icons";

// Shared by the project list and the project page. A module of its own so that
// neither imports the other: the code graph found that cycle (PHASE 27).

export const TYPE_ICON: Record<string, IconName> = {
  WEB_APP: "dashboard", BACKEND: "models", MOBILE: "file", DESKTOP: "mockups", AI_ML: "sparkle", GAME: "play",
  THREE_D: "models", DATA: "usage", AUTOMATION: "refresh", API: "integrations", FULL_STACK: "software", OTHER: "plus",
};

export const AUTONOMY: Record<AutonomyLevel, { label: string; hint: string }> = {
  GUIDED: { label: "Guidato — imparo facendo", hint: "Scrivi tu il codice; gli agenti spiegano, chiedono e verificano." },
  SUPERVISED: { label: "Supervisionato", hint: "Gli agenti propongono lavoro completo; tu integri e controlli." },
  DELEGATED: { label: "Delegato", hint: "Gli agenti implementano, testano e documentano; tu revisioni." },
  FINAL_REVIEW: { label: "Solo review finale", hint: "Gli agenti coordinano; tu fai la verifica finale." },
};

