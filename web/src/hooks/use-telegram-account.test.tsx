import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  TelegramAccountProvider,
  useTelegramAccount,
} from "@/hooks/use-telegram-account";

const mocks = vi.hoisted(() => ({
  search: "",
  push: vi.fn(),
  trigger: vi.fn().mockResolvedValue(undefined),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push }),
  useSearchParams: () => new URLSearchParams(mocks.search),
}));

vi.mock("swr", () => ({
  default: () => ({
    data: [],
    isLoading: false,
    isValidating: false,
  }),
}));

vi.mock("swr/mutation", () => ({
  default: () => ({ trigger: mocks.trigger }),
}));

function SelectedAccount() {
  const { accountId } = useTelegramAccount();
  return <span>{accountId ?? "none"}</span>;
}

describe("TelegramAccountProvider", () => {
  afterEach(() => {
    cleanup();
    mocks.search = "";
    mocks.push.mockReset();
    mocks.trigger.mockClear();
  });

  it("tracks account IDs introduced by client-side URL navigation", async () => {
    const view = render(
      <TelegramAccountProvider>
        <SelectedAccount />
      </TelegramAccountProvider>,
    );
    expect(screen.getByText("none")).toBeVisible();

    mocks.search = "id=7789851018&chatId=42";
    view.rerender(
      <TelegramAccountProvider>
        <SelectedAccount />
      </TelegramAccountProvider>,
    );

    await waitFor(() => expect(screen.getByText("7789851018")).toBeVisible());
    expect(mocks.trigger).toHaveBeenCalledWith({ accountId: "7789851018" });
  });
});
