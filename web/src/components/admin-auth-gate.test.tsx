import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { AdminAuthGate } from "@/components/admin-auth-gate";
import { AdminSessionProvider } from "@/hooks/use-admin-session";
import { SESSION_TERMINAL_EVENT } from "@/lib/api";
import { I18nProvider } from "@/hooks/use-i18n";
import { LOCALE_STORAGE_KEY, translate } from "@/i18n";

function jsonResponse(status: number, value: unknown): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("AdminAuthGate", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(cleanup);

  it("keeps private UI locked until login succeeds", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(200, { required: false }))
      .mockResolvedValueOnce(
        jsonResponse(401, {
          error: { code: "AUTHENTICATION_REQUIRED", message: "Sign in" },
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, {
          authenticated: true,
          username: "owner",
          idleExpiresAt: 10,
          absoluteExpiresAt: 20,
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    render(
      <AdminSessionProvider>
        <AdminAuthGate>
          <div>Private Telegram files</div>
        </AdminAuthGate>
      </AdminSessionProvider>,
    );

    expect(await screen.findByText("Administrator sign in")).toBeVisible();
    expect(
      screen.getByRole("img", { name: "Telegram Files logo" }),
    ).toBeVisible();
    expect(
      screen.queryByText(
        "Telegram Files management APIs and WebSocket access require an active administrator session.",
      ),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("Private Telegram files"),
    ).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Username"), {
      target: { value: "owner" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "correct horse battery staple" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByText("Private Telegram files")).toBeVisible();
    expect(fetchMock).toHaveBeenLastCalledWith(
      expect.stringContaining("/auth/login"),
      expect.objectContaining({ method: "POST", credentials: "include" }),
    );
    expect(
      screen.queryByRole("button", { name: "Log out administrator" }),
    ).not.toBeInTheDocument();
  });

  it("switches the sign-in language without resetting the current form", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(200, { required: false }))
      .mockResolvedValueOnce(
        jsonResponse(401, {
          error: { code: "AUTHENTICATION_REQUIRED", message: "Sign in" },
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse(200, {
          authenticated: true,
          username: "owner",
          idleExpiresAt: 10,
          absoluteExpiresAt: 20,
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const entries = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => entries.get(key) ?? null,
      setItem: (key: string, value: string) => entries.set(key, value),
    });
    try {
      render(
        <I18nProvider>
          <AdminSessionProvider>
            <AdminAuthGate>
              <div>Private Telegram files</div>
            </AdminAuthGate>
          </AdminSessionProvider>
        </I18nProvider>,
      );
      expect(await screen.findByText("Administrator sign in")).toBeVisible();
      fireEvent.change(screen.getByLabelText("Username"), {
        target: { value: "owner" },
      });
      fireEvent.change(screen.getByRole("combobox", { name: "Language" }), {
        target: { value: "zh-CN" },
      });
      expect(
        screen.queryByText("Administrator sign in"),
      ).not.toBeInTheDocument();
      expect(screen.getByLabelText(translate("zh-CN", "Username"))).toHaveValue(
        "owner",
      );
      fireEvent.change(screen.getByLabelText(translate("zh-CN", "Password")), {
        target: { value: "correct horse battery staple" },
      });
      fireEvent.click(
        screen.getByRole("button", { name: translate("zh-CN", "Sign in") }),
      );
      expect(await screen.findByText("Private Telegram files")).toBeVisible();
      expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("zh-CN");
    } finally {
      vi.unstubAllGlobals();
      document.documentElement.lang = "en";
    }
  });

  it("shows the Telegram Files logo during administrator bootstrap", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(jsonResponse(200, { required: true })),
    );

    render(
      <AdminSessionProvider>
        <AdminAuthGate>
          <div>Private Telegram files</div>
        </AdminAuthGate>
      </AdminSessionProvider>,
    );

    expect(
      await screen.findByText("Create the first administrator"),
    ).toBeVisible();
    expect(
      screen.getByRole("img", { name: "Telegram Files logo" }),
    ).toBeVisible();
  });

  it("locks the UI immediately when a session is revoked", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(jsonResponse(200, { required: false }))
        .mockResolvedValueOnce(
          jsonResponse(200, {
            authenticated: true,
            username: "owner",
            idleExpiresAt: 10,
            absoluteExpiresAt: 20,
          }),
        ),
    );

    render(
      <AdminSessionProvider>
        <AdminAuthGate>
          <div>Private Telegram files</div>
        </AdminAuthGate>
      </AdminSessionProvider>,
    );

    expect(await screen.findByText("Private Telegram files")).toBeVisible();
    window.dispatchEvent(new CustomEvent(SESSION_TERMINAL_EVENT));

    await waitFor(() =>
      expect(
        screen.queryByText("Private Telegram files"),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "expired or was revoked",
    );
  });
});
