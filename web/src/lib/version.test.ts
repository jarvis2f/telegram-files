import { describe, expect, it } from "vitest";
import { compareVersions, isVersionNewer } from "@/lib/version";

describe("version comparison", () => {
  it("treats v-prefixed and plain versions as equal", () => {
    expect(compareVersions("v0.4.0", "0.4.0")).toBe(0);
  });

  it("detects latest versions that are newer than current", () => {
    expect(isVersionNewer("0.4.1", "0.4.0")).toBe(true);
    expect(isVersionNewer("1.0.0", "0.9.9")).toBe(true);
  });

  it("does not report an update when current is newer than latest", () => {
    expect(isVersionNewer("0.4.0", "0.5.0")).toBe(false);
  });

  it("handles missing patch segments", () => {
    expect(compareVersions("0.4", "0.4.0")).toBe(0);
    expect(isVersionNewer("0.4.1", "0.4")).toBe(true);
  });
});
