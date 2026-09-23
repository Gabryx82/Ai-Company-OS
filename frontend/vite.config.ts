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
  },
});
