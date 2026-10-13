import { describe, expect, it } from "vitest";
import { nextSelection } from "@/lib/range-selection";

const items = [10, 20, 30, 40, 50].map((id) => ({ id }));

describe("nextSelection", () => {
  it("toggles a single row on a plain click", () => {
    expect(nextSelection(new Set(), items, 30, null, false)).toEqual(
      new Set([30]),
    );
    expect(nextSelection(new Set([30]), items, 30, 30, false)).toEqual(
      new Set(),
    );
  });

  it("selects every row between the anchor and a shift-click", () => {
    expect(nextSelection(new Set([20]), items, 40, 20, true)).toEqual(
      new Set([20, 30, 40]),
    );
  });

  it("works when the shift-click is above the anchor", () => {
    expect(nextSelection(new Set([40]), items, 20, 40, true)).toEqual(
      new Set([20, 30, 40]),
    );
  });

  it("keeps rows that were already selected outside the range", () => {
    expect(nextSelection(new Set([10, 20]), items, 50, 20, true)).toEqual(
      new Set([10, 20, 30, 40, 50]),
    );
  });

  it("deselects the range when the clicked row was selected", () => {
    expect(
      nextSelection(new Set([10, 20, 30, 40, 50]), items, 40, 20, true),
    ).toEqual(new Set([10, 50]));
  });

  it("falls back to a plain toggle without an anchor", () => {
    expect(nextSelection(new Set(), items, 40, null, true)).toEqual(
      new Set([40]),
    );
  });

  it("falls back to a plain toggle when the anchor is no longer listed", () => {
    expect(nextSelection(new Set(), items, 40, 999, true)).toEqual(
      new Set([40]),
    );
  });

  it("does not mutate the previous selection", () => {
    const selected = new Set([20]);
    nextSelection(selected, items, 40, 20, true);
    expect(selected).toEqual(new Set([20]));
  });
});
