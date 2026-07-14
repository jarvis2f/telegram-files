import { describe, expect, it } from "vitest";
import { applyColumnPreferences } from "@/lib/column-preferences";

describe("applyColumnPreferences", () => {
  const defaults = [
    { id: "content", isVisible: true, label: "Content" },
    { id: "status", isVisible: true, label: "Status" },
    { id: "actions", isVisible: true, label: "Actions" },
  ];

  it("restores saved order and visibility", () => {
    expect(
      applyColumnPreferences(defaults, [
        { id: "status", isVisible: false },
        { id: "content", isVisible: true },
        { id: "actions", isVisible: true },
      ]),
    ).toEqual([
      { id: "status", isVisible: false, label: "Status" },
      { id: "content", isVisible: true, label: "Content" },
      { id: "actions", isVisible: true, label: "Actions" },
    ]);
  });

  it("drops removed columns and appends newly introduced columns", () => {
    expect(
      applyColumnPreferences(defaults, [
        { id: "removed", isVisible: false },
        { id: "content", isVisible: false },
      ]),
    ).toEqual([
      { id: "content", isVisible: false, label: "Content" },
      { id: "status", isVisible: true, label: "Status" },
      { id: "actions", isVisible: true, label: "Actions" },
    ]);
  });

  it("ignores duplicate saved entries", () => {
    expect(
      applyColumnPreferences(defaults, [
        { id: "content", isVisible: false },
        { id: "content", isVisible: true },
      ]).filter((column) => column.id === "content"),
    ).toEqual([{ id: "content", isVisible: false, label: "Content" }]);
  });
});
