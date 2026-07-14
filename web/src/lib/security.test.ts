import { describe, expect, it } from "vitest";

import { browserWriteHeaders, isSessionTerminal, requiresCsrf } from "./security";

describe("browser security boundary", () => {
  it("requires CSRF only for unsafe methods", () => {
    expect(requiresCsrf("GET")).toBe(false);
    expect(requiresCsrf("POST")).toBe(true);
    expect(browserWriteHeaders("POST", "fixture-csrf")).toEqual({
      "X-CSRF-Token": "fixture-csrf",
    });
    expect(() => browserWriteHeaders("DELETE")).toThrow();
  });

  it("treats authentication and authorization failures as terminal sessions", () => {
    expect(isSessionTerminal(401)).toBe(true);
    expect(isSessionTerminal(403)).toBe(true);
    expect(isSessionTerminal(429)).toBe(false);
  });
});
