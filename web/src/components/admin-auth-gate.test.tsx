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
