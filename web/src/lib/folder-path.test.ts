import { describe, expect, it } from "vitest";

import { isValidFolderPath } from "./folder-path";

describe("isValidFolderPath", () => {
  it("accepts paths with either separator and optional leading or trailing separators", () => {
    expect(isValidFolderPath("downloads/archive")).toBe(true);
    expect(isValidFolderPath("\\downloads\\archive\\")).toBe(true);
    expect(isValidFolderPath("/downloads/archive/")).toBe(true);
  });

  it("rejects empty segments and invalid path characters", () => {
    expect(isValidFolderPath("downloads//archive")).toBe(false);
    expect(isValidFolderPath("downloads/archive:name")).toBe(false);
    expect(isValidFolderPath("downloads/archive*")).toBe(false);
  });

  it("rejects excessively long paths without expensive backtracking", () => {
    expect(isValidFolderPath("!".repeat(4097))).toBe(false);
  });
});
