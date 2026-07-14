import { describe, expect, it } from "vitest";

import { describeShareError } from "./share-errors";

describe("share error descriptions", () => {
  it("explains interactive platform access redirects", () => {
    expect(describeShareError("PLATFORM_ACCESS_BLOCKED")).toContain("interactive access gateway");
  });

  it("keeps unknown platform codes visible", () => {
    expect(describeShareError("PLATFORM_HTTP_418")).toContain("PLATFORM_HTTP_418");
  });
});
