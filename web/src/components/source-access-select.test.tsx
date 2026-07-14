import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SourceAccessSelect } from "@/components/source-access-select";

describe("SourceAccessSelect", () => {
  afterEach(cleanup);

  it("keeps the selected access explanation associated with the control", () => {
    const { rerender } = render(
      <SourceAccessSelect value="OWNER_ONLY" onValueChange={vi.fn()} />,
    );

    expect(screen.getByRole("button")).toHaveAccessibleDescription(
      "Only the node that published this source may fetch the file from Telegram. Best for private chats, groups, and channels; no other node receives the message locator.",
    );

    rerender(<SourceAccessSelect value="PUBLIC" onValueChange={vi.fn()} />);

    expect(screen.getByRole("button")).toHaveAccessibleDescription(
      "Eligible nodes may fetch the file from the public t.me message URL you provide. Use this only when the message is accessible without joining a group or granting additional access.",
    );
  });

  it("updates one English detail panel as keyboard focus moves", async () => {
    render(<SourceAccessSelect value="OWNER_ONLY" onValueChange={vi.fn()} />);

    const trigger = screen.getByRole("button");
    trigger.focus();
    fireEvent.keyDown(trigger, { key: "Enter" });

    const members = await screen.findByRole("menuitemradio", {
      name: "Members with independent proof. Another node may fetch the file only after it independently proves access to the same Telegram source. Belonging to the same user or merely being signed in is not sufficient.",
    });
    fireEvent.focus(members);

    expect(
      screen.getByText(
        "Another node may fetch the file only after it independently proves access to the same Telegram source. Belonging to the same user or merely being signed in is not sufficient.",
      ),
    ).toBeVisible();
  });
});
