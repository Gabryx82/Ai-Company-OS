import { afterEach } from "vitest";
import { cleanup, configure } from "@testing-library/react";

// ADR-045 §7: `findBy…` and `waitFor` give up after 1 s by default. In the whole suite, 35 files in parallel, a render
// can take longer than that and a correct test fails (seen 2026-11-12: OrchestratorPanel, 1,17 s, green alone and on the
// next run). The same reason as `testTimeout` in vite.config.ts (TD-99): what is slow, not what is wrong.
configure({ asyncUtilTimeout: 5_000 });

afterEach(() => cleanup());
