import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@fontsource-variable/geist";
import { App } from "./App";
import { applyTheme, storedTheme } from "./theme";
import "./styles.css";

// Before the first render, so the console never flashes the other theme.
applyTheme(storedTheme());

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
