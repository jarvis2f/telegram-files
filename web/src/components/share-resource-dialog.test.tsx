import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ShareResourceDialog } from "@/components/share-resource-dialog";
import { request } from "@/lib/api";
import type { TelegramFile } from "@/lib/types";

vi.mock("@/lib/api", () => ({
  request: vi.fn(),
}));

vi.mock("@/hooks/use-toast", () => ({
  toast: vi.fn(),
}));

vi.mock("@/hooks/use-share-publication-policy", () => ({
  useSharePublicationPolicy: () => ({
    defaultDecision: "ALLOW",
    defaultCategoryId: "file",
    categories: [
      { id: "file", label: "File", defaultForFileTypes: ["file"] },
      { id: "video", label: "Video", defaultForFileTypes: ["video"] },
    ],
    shareRules: [],
  }),
}));

const requestMock = vi.mocked(request);

class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}

vi.stubGlobal("ResizeObserver", NoopResizeObserver);

describe("ShareResourceDialog", () => {
  afterEach(() => {
    cleanup();
    requestMock.mockReset();
  });

  it("hides publication policy and publishes downloaded files only", async () => {
    requestMock.mockResolvedValue({
      status: "PUBLISHED",
      lastErrorCode: null,
    });

    render(
      <ShareResourceDialog
        file={telegramFile({ downloadStatus: "completed" })}
        open
        onOpenChange={() => undefined}
      />,
    );

    expect(screen.queryByText("Publication policy")).not.toBeInTheDocument();
    expect(screen.queryByText("Index now")).not.toBeInTheDocument();
    expect(
      screen.queryByText("Allow on-demand Telegram download"),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Publish resource" }));

    await waitFor(() => expect(requestMock).toHaveBeenCalledTimes(1));
    const [, options] = requestMock.mock.calls[0]!;
    expect(JSON.parse(String(options?.body))).toMatchObject({
      immediateReseed: true,
      indexOnly: false,
      autoDownloadOnDemand: false,
    });
  });

  it("does not submit files that are not fully downloaded", () => {
    render(
      <ShareResourceDialog
        file={telegramFile({ downloadStatus: "idle" })}
        open
        onOpenChange={() => undefined}
      />,
    );

    expect(
      screen.getByRole("button", { name: "Publish resource" }),
    ).toBeDisabled();
  });
});

function telegramFile(overrides: Partial<TelegramFile> = {}): TelegramFile {
  return {
    id: 1,
    telegramId: 10,
    uniqueId: "unique-file",
    messageId: 20,
    chatId: 30,
    fileName: "archive.zip",
    type: "file",
    mimeType: "application/zip",
    size: 100 * 1024 * 1024,
    downloadedSize: 100 * 1024 * 1024,
    downloadStatus: "completed",
    date: 0,
    formatDate: "2026-07-30",
    caption: "Archive",
    localPath: "/tmp/archive.zip",
    hasSensitiveContent: false,
    startDate: 0,
    completionDate: 0,
    originalDeleted: false,
    loaded: true,
    threadChatId: 0,
    messageThreadId: 0,
    reactionCount: 0,
    ...overrides,
  };
}
