import { describe, expect, it } from "vitest";

import { mergeShareTags } from "@/lib/share-tags";

describe("mergeShareTags", () => {
  it("keeps manual tags and appends hashtags from caption", () => {
    expect(
      mergeShareTags(
        "manual",
        "#汽水音乐 多开版NoAd\n\n若提示不安全划掉后台多试几次",
        false,
      ),
    ).toBe("manual, 汽水音乐");
  });

  it("deduplicates tags and appends R18 for sensitive content", () => {
    expect(mergeShareTags("Alpha", "Scene #alpha #beta", true)).toBe(
      "Alpha, beta, R18",
    );
  });
});
