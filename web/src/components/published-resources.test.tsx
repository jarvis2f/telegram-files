import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import type React from "react";
import { SWRConfig } from "swr";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  EditPublishedResourceDialog,
  PublishedResources,
  seedResourceDetailsUrl,
  type PublishedSource,
} from "@/components/published-resources";
import { request } from "@/lib/api";

vi.mock("@/lib/api", () => ({
  request: vi.fn(),
}));

vi.mock("@/hooks/use-toast", () => ({
  toast: vi.fn(),
}));

const requestMock = vi.mocked(request);

describe("EditPublishedResourceDialog", () => {
  afterEach(() => {
    cleanup();
    requestMock.mockReset();
  });

  it("does not reset in-progress edits when the same source refreshes", () => {
    const source = publishedSource({ description: "old description" });
    const { rerender } = render(
      <EditPublishedResourceDialog
        source={source}
        onOpenChange={() => undefined}
        onSaved={async () => undefined}
      />,
    );
    const description = screen.getByLabelText("Description");

    fireEvent.change(description, {
      target: { value: "new line one\nnew line two" },
    });
    rerender(
      <EditPublishedResourceDialog
        source={publishedSource({ description: "old description" })}
        onOpenChange={() => undefined}
        onSaved={async () => undefined}
      />,
    );

    expect(description).toHaveValue("new line one\nnew line two");
  });
});

describe("PublishedResources", () => {
  afterEach(() => {
    cleanup();
    requestMock.mockReset();
    vi.unstubAllGlobals();
  });

  it("copies the seed platform resource details page", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    requestMock.mockImplementation(async (path: string) => {
      if (path === "/share/device/status") {
        return { platformUrl: "https://seed.example.test" };
      }
      if (path === "/share/resources?page=1&pageSize=10") {
        return {
          items: [publishedSource()],
          page: 1,
          pageSize: 10,
          total: 1,
        };
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    renderWithSWR(<PublishedResources />);

    fireEvent.click(
      await screen.findByRole("button", {
        name: "Copy platform link for Indexed title",
      }),
    );

    await waitFor(() =>
      expect(writeText).toHaveBeenCalledWith(
        "https://seed.example.test/resources/seed-resource-id",
      ),
    );
  });

  it("renders pagination controls when resources exceed one page", async () => {
    requestMock.mockImplementation(async (path: string) => {
      if (path === "/share/device/status") {
        return { platformUrl: "https://seed.example.test" };
      }
      if (path === "/share/resources?page=1&pageSize=10") {
        return {
          items: [publishedSource()],
          page: 1,
          pageSize: 10,
          total: 11,
        };
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    renderWithSWR(<PublishedResources />);

    expect(await screen.findByText("Showing 1-10 of 11")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();
    expect(screen.getByText("1 / 2")).toBeInTheDocument();
  });
});

describe("seedResourceDetailsUrl", () => {
  it("uses the platform web route instead of the API route", () => {
    expect(
      seedResourceDetailsUrl("https://seed.example.test", "resource/id"),
    ).toBe("https://seed.example.test/resources/resource%2Fid");
  });
});

function renderWithSWR(element: React.ReactElement) {
  return render(
    <SWRConfig value={{ provider: () => new Map(), dedupingInterval: 0 }}>
      {element}
    </SWRConfig>,
  );
}

function publishedSource(
  overrides: Partial<PublishedSource> = {},
): PublishedSource {
  return {
    sourceId: "source-local-id",
    resourceId: "seed-resource-id",
    fileUniqueId: "unique-file",
    fileName: "archive.zip",
    fileSize: "1024",
    title: "Indexed title",
    description: null,
    tags: ["docs"],
    category: "archive",
    status: "PUBLISHED",
    accessScope: "OWNER_ONLY",
    publicMessageUrl: null,
    downloaded: true,
    lastErrorCode: null,
    ...overrides,
  };
}
