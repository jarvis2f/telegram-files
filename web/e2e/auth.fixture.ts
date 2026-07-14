import { test as base } from "@playwright/test";

export const test = base.extend<{ csrfToken: string }>({
  csrfToken: async ({ context }, provide) => {
    await context.addCookies([
      {
        name: "tf_csrf",
        value: "e2e-csrf-token",
        url: "http://localhost:3000",
        sameSite: "Strict",
      },
    ]);
    await provide("e2e-csrf-token");
  },
});

export { expect } from "@playwright/test";
