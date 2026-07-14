import { cleanup, render, screen, waitFor } from "@testing-library/react";
import type React from "react";
import { SWRConfig } from "swr";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SharePublicationRules } from "@/components/share-publication-rules";
import { request } from "@/lib/api";

const hookState = vi.hoisted(() => ({
  shareEnabled: true,
  isBound: false,
}));

vi.mock("@/lib/api", () => ({
  request: vi.fn(),
}));

vi.mock("@/hooks/use-share-enabled", () => ({
  useShareEnabled: () => hookState.shareEnabled,
}));

vi.mock("@/hooks/use-platform-binding-status", () => ({
  usePlatformBindingStatus: () => ({
    isBound: hookState.isBound,
    isPending: false,
    data: hookState.isBound ? { status: "BOUND" } : { status: "UNBOUND" },
  }),
}));

const requestMock = vi.mocked(request);

describe("SharePublicationRules", () => {
  afterEach(() => {
    cleanup();
    requestMock.mockReset();
    hookState.shareEnabled = true;
    hookState.isBound = false;
  });

  it("does not render or fetch rules while the node is unbound", () => {
    renderWithSWR(<SharePublicationRules />);

    expect(screen.queryByText("Sharing rules")).not.toBeInTheDocument();
    expect(requestMock).not.toHaveBeenCalled();
  });

  it("fetches and renders rules after the node is bound", async () => {
    hookState.isBound = true;
    requestMock.mockResolvedValue({
      defaultDecision: "ALLOW",
      defaultCategoryId: "file",
      categories: [{ id: "file", label: "File", defaultForFileTypes: [] }],
      shareRules: [],
    });

    renderWithSWR(<SharePublicationRules />);

    expect(await screen.findByText("Sharing rules")).toBeInTheDocument();
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("/share/publication-policy"),
    );
  });
});

function renderWithSWR(element: React.ReactElement) {
  return render(
    <SWRConfig value={{ provider: () => new Map(), dedupingInterval: 0 }}>
      {element}
    </SWRConfig>,
  );
}
