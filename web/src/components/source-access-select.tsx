"use client";

import { useId, useState } from "react";
import { ChevronDown } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

export type AccessScope = "PUBLIC" | "MEMBER_ACCESS" | "OWNER_ONLY";

const ACCESS_SCOPE_OPTIONS: ReadonlyArray<{
  value: AccessScope;
  label: string;
  description: string;
}> = [
  {
    value: "OWNER_ONLY",
    label: "Owner node only",
    description:
      "Only the node that published this source may fetch the file from Telegram. Best for private chats, groups, and channels; no other node receives the message locator.",
  },
  {
    value: "MEMBER_ACCESS",
    label: "Members with independent proof",
    description:
      "Another node may fetch the file only after it independently proves access to the same Telegram source. Belonging to the same user or merely being signed in is not sufficient.",
  },
  {
    value: "PUBLIC",
    label: "Public Telegram message",
    description:
      "Eligible nodes may fetch the file from the public t.me message URL you provide. Use this only when the message is accessible without joining a group or granting additional access.",
  },
];

export function SourceAccessSelect({
  id,
  value,
  onValueChange,
}: {
  id?: string;
  value: AccessScope;
  onValueChange: (value: AccessScope) => void;
}) {
  const generatedId = useId();
  const triggerId = id ?? `source-access-${generatedId}`;
  const descriptionId = `${triggerId}-description`;
  const [highlightedScope, setHighlightedScope] = useState<AccessScope>(value);
  const selected = ACCESS_SCOPE_OPTIONS.find(
    (option) => option.value === value,
  )!;
  const highlighted =
    ACCESS_SCOPE_OPTIONS.find((option) => option.value === highlightedScope) ??
    selected;

  return (
    <div className="flex flex-col gap-2">
      <DropdownMenu
        onOpenChange={(open) => {
          if (open) setHighlightedScope(value);
        }}
      >
        <DropdownMenuTrigger asChild>
          <Button
            id={triggerId}
            type="button"
            variant="outline"
            className="w-full justify-between"
            aria-describedby={descriptionId}
          >
            {selected.label}
            <ChevronDown data-icon="inline-end" className="opacity-50" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="start"
          className="w-[min(42rem,calc(100vw-2rem))] p-2"
        >
          <div className="grid gap-2 md:grid-cols-[minmax(15rem,0.9fr)_minmax(16rem,1.1fr)]">
            <DropdownMenuRadioGroup
              value={value}
              onValueChange={(nextValue) =>
                onValueChange(nextValue as AccessScope)
              }
            >
              {ACCESS_SCOPE_OPTIONS.map((option) => (
                <DropdownMenuRadioItem
                  key={option.value}
                  value={option.value}
                  aria-label={`${option.label}. ${option.description}`}
                  onFocus={() => setHighlightedScope(option.value)}
                  onPointerMove={() => setHighlightedScope(option.value)}
                >
                  {option.label}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
            <div
              className="flex min-h-24 flex-col gap-1 rounded-md bg-muted p-3"
              aria-live="polite"
            >
              <p className="text-sm font-medium">{highlighted.label}</p>
              <p className="text-sm text-muted-foreground">
                {highlighted.description}
              </p>
            </div>
          </div>
        </DropdownMenuContent>
      </DropdownMenu>
      <p
        id={descriptionId}
        className="text-sm text-muted-foreground"
        aria-live="polite"
      >
        {selected.description}
      </p>
    </div>
  );
}
