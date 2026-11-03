import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TelegramChatProvider, useTelegramChat } from "./use-telegram-chat";

const mocks = vi.hoisted(() => ({ search: "", push: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push }),
  useSearchParams: () => new URLSearchParams(mocks.search),
}));
vi.mock("swr", () => ({
  default: () => ({ data: [], isLoading: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-toast", () => ({ useToast: () => ({ toast: vi.fn() }) }));

function Chat() {
  const { chat, handleSavedMessages } = useTelegramChat();
  return (
    <>
      <span>{chat?.name ?? "No chat"}</span>
      <button onClick={handleSavedMessages}>Saved</button>
    </>
  );
}

describe("Saved Messages shortcut", () => {
  afterEach(() => {
    cleanup();
    mocks.search = "";
    mocks.push.mockReset();
  });

  it("navigates to the account's own chat even when it is absent from results", () => {
    mocks.search = "id=123&chatId=456";
    render(
      <TelegramChatProvider>
        <Chat />
      </TelegramChatProvider>,
    );
    fireEvent.click(screen.getByRole("button", { name: "Saved" }));
    expect(mocks.push).toHaveBeenCalledWith("/accounts?id=123&chatId=123");
  });

  it("identifies the selected saved chat while chat results are unavailable", () => {
    mocks.search = "id=123&chatId=123";
    render(
      <TelegramChatProvider>
        <Chat />
      </TelegramChatProvider>,
    );
    expect(screen.getByText("Saved Messages")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Saved" }));
    expect(mocks.push).not.toHaveBeenCalled();
  });
});
