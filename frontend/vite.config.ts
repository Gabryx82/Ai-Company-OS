import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// The dev server runs on the one origin the control plane's dev profile allows
// (aicos.cors.allowed-origins, TASK-014): http://localhost:5173. strictPort, so
// that a busy port fails loudly instead of moving to an origin CORS refuses.
export default defineConfig({
  plugins: [react()],
  server: { host: "localhost", port: 5173, strictPort: true },
  preview: { host: "localhost", port: 5173, strictPort: true },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    // PHASE 36 (closes TD-99): under load -- the whole suite, after the Java one -- the lazy three.js chunk of
    // the ecosystem view and the Knowledge Hub's largest render take 6-7 s in jsdom. A timeout fails nothing
    // that is wrong, only what is slow: 15 s for every test, the same limit the runs of PHASE 35 used by hand.
    testTimeout: 15_000,
  },
});
