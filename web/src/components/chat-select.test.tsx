import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import ChatSelect from "@/components/chat-select";

const mockIsMobile = vi.hoisted(() => vi.fn(() => true));

vi.mock("@/hooks/use-is-mobile", () => ({
  default: mockIsMobile,
}));

vi.mock("@/hooks/use-telegram-chat", () => ({
  useTelegramChat: () => ({
    isLoading: false,
    handleQueryChange: vi.fn(),
    chats: [
      {
        id: "chat-1",
        name: "Design",
        type: "group",
        unreadCount: 2,
      },
    ],
    chat: undefined,
    handleChatChange: vi.fn(),
    handleArchivedChange: vi.fn(),
  }),
}));

class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

vi.stubGlobal("ResizeObserver", NoopResizeObserver);
Element.prototype.scrollIntoView = vi.fn();

describe("ChatSelect", () => {
  afterEach(() => {
    cleanup();
    mockIsMobile.mockReturnValue(true);
  });

  it("opens the mobile search panel without a popper transform wrapper", () => {
    render(<ChatSelect disabled={false} />);

    fireEvent.click(screen.getByRole("combobox"));

    expect(screen.getByPlaceholderText("Search chat...")).toBeVisible();
    expect(
      document.querySelector("[data-radix-popper-content-wrapper]"),
    ).not.toBeInTheDocument();
  });
});
