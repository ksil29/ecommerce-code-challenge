import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

window.scrollTo = vi.fn();

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  window.localStorage.clear();
  window.sessionStorage.clear();
  document.title = "Shop";
});
