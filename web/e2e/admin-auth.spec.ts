import type { Page, Route } from "@playwright/test";
import { expect, test } from "./auth.fixture";

const apiHeaders = {
  "access-control-allow-credentials": "true",
  "access-control-allow-headers": "content-type,x-csrf-token",
  "access-control-allow-methods": "GET,POST,OPTIONS",
  "access-control-allow-origin": "http://localhost:3000",
  "content-type": "application/json",
};

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({
    status,
    headers: apiHeaders,
    body: JSON.stringify(body),
  });
}

async function installApi(
  page: Page,
  options: {
    bootstrapRequired: boolean;
    onLogout?: (csrfHeader: string | undefined) => void;
  },
) {
  await page.route("http://localhost:8080/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers: apiHeaders });
      return;
    }
    if (path === "/auth/bootstrap/status") {
      await json(route, 200, { required: options.bootstrapRequired });
      return;
    }
    if (path === "/auth/session") {
      await json(route, 401, {
        error: { code: "UNAUTHENTICATED", message: "Sign in" },
      });
      return;
    }
    if (path === "/auth/login" || path === "/auth/bootstrap") {
      await json(route, 200, {
        authenticated: true,
        username: "admin",
        idleExpiresAt: Date.now() + 60_000,
        absoluteExpiresAt: Date.now() + 120_000,
      });
      return;
    }
    if (path === "/auth/logout") {
      options.onLogout?.(request.headers()["x-csrf-token"]);
      await route.fulfill({ status: 204, headers: apiHeaders });
      return;
    }
    if (path === "/telegrams") {
      await json(route, 200, []);
      return;
    }
    if (path === "/files/count") {
      await json(route, 200, {
        downloading: 0,
        completed: 0,
        downloadedSize: 0,
      });
      return;
    }
    await json(route, 404, { error: { code: "NOT_FOUND", message: path } });
  });
}

test("keeps private UI unmounted until login and sends CSRF on logout", async ({
  page,
  csrfToken,
}) => {
  let logoutCsrf: string | undefined;
  await installApi(page, {
    bootstrapRequired: false,
    onLogout: (header) => {
      logoutCsrf = header;
    },
  });

  await page.goto("/");
  await expect(
    page.getByText("Administrator sign in", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "No Accounts Found" }),
  ).toHaveCount(0);

  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(
    page.getByRole("heading", { name: "No Accounts Found" }),
  ).toBeVisible();
  const logoutButton = page.getByRole("button", {
    name: "Log out",
    exact: true,
  });
  await logoutButton.hover();
  await expect(page.getByRole("tooltip", { name: "Log out" })).toBeVisible();
  await logoutButton.click();
  await expect(
    page.getByText("Administrator sign in", { exact: true }),
  ).toBeVisible();
  expect(logoutCsrf).toBe(csrfToken);
});

test("supports the local-network first-administrator bootstrap flow", async ({
  page,
}) => {
  await installApi(page, { bootstrapRequired: true });

  await page.goto("/");
  await expect(
    page.getByText("Create the first administrator", { exact: true }),
  ).toBeVisible();
  await page.getByLabel("One-time bootstrap code").fill("local-bootstrap-code");
  await page.getByLabel("Username").fill("admin");
  await page.getByLabel("Password").fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Create administrator" }).click();

  await expect(
    page.getByRole("heading", { name: "No Accounts Found" }),
  ).toBeVisible();
});
