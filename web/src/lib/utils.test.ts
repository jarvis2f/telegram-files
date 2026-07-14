import { describe, expect, it } from "vitest";
import { split } from "@/lib/utils";

describe("split", () => {
  it("trims values and discards empty list entries", () => {
    expect(split(",", " movies, , music ,, ")).toEqual(["movies", "music"]);
  });

  it("returns an empty list for missing input", () => {
    expect(split(",", undefined)).toEqual([]);
  });
});
