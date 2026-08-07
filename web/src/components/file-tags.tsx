"use client";

import { type TelegramFile } from "@/lib/types";
import { Check, LoaderIcon, Plus, Tag } from "lucide-react";
import React, { useEffect, useState } from "react";
import { cn, split } from "@/lib/utils";
import { useSettings } from "@/hooks/use-settings";
import useSWRMutation from "swr/mutation";
import { POST } from "@/lib/api";
import { useDebounce } from "use-debounce";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import { Button } from "@/components/ui/button";

function useBatchUpdateTags({
  files,
  onTagsUpdate,
}: {
  files: TelegramFile[];
  onTagsUpdate?: (tags: string[]) => void;
}) {
  const [tags, setTags] = useState<string[]>([]);

  useEffect(() => {
    const allTags = files.flatMap((file) => split(",", file.tags ?? ""));
    setTags(Array.from(new Set(allTags)));
  }, [files]);

  const { trigger, isMutating } = useSWRMutation(
    "/files/update-tags",
    (
      key,
      {
        arg,
      }: {
        arg: {
          files: Array<{
            telegramId: number;
            chatId: number;
            messageId: number;
            uniqueId: string;
            fileId: number;
          }>;
          tags: string;
        };
      },
    ) => POST(key, arg),
    {
      onSuccess: () => {
        onTagsUpdate?.(tags);
      },
    },
  );

  const toggleUpdateTags = async (overrideTags?: string[]) => {
    const targetTags = overrideTags ?? tags;
    await trigger({
      files: files.map((file) => ({
        telegramId: file.telegramId ?? 0,
        chatId: file.chatId ?? 0,
        messageId: file.messageId ?? 0,
        uniqueId: file.uniqueId ?? "",
        fileId: file.id ?? 0,
      })),
      tags: targetTags.join(","),
    });
  };

  const [debounceMutating] = useDebounce(isMutating, 200, {
    leading: true,
    maxWait: 400,
  });

  return {
    tags,
    setTags,
    toggleUpdateTags,
    isMutating: debounceMutating,
  };
}

export function useUpdateTags({
  file,
  onTagsUpdate,
}: {
  file: TelegramFile;
  onTagsUpdate?: (tags: string[]) => void;
}) {
  const [tags, setTags] = useState<string[]>(split(",", file?.tags));

  useEffect(() => {
    setTags(split(",", file?.tags));
  }, [file?.tags]);

  const { trigger, isMutating } = useSWRMutation(
    `/file/${file.uniqueId}/update-tags`,
    (key, { arg }: { arg: { tags: string } }) => POST(key, arg),
  );

  const toggleUpdateTags = async (overrideTags?: string[]) => {
    const targetTags = overrideTags ?? tags;
    setTags(targetTags);
    onTagsUpdate?.(targetTags);
    const newTags = targetTags.join(",");
    try {
      await trigger({ tags: newTags });
    } catch (err) {
      console.error("Failed to update tags:", err);
    }
  };

  const [debounceMutating] = useDebounce(isMutating, 200, {
    leading: true,
    maxWait: 400,
  });

  return {
    tags,
    setTags,
    toggleUpdateTags,
    isMutating: debounceMutating,
  };
}

interface FileTagsProps {
  file: TelegramFile;
  onFileChange?: (updatedFile: TelegramFile) => void;
  onTagsUpdate?: (tags: string[]) => void;
  className?: string;
  side?: "top" | "bottom" | "left" | "right";
  align?: "start" | "center" | "end";
  isPreviewOverlay?: boolean;
}

export default function FileTags({
  file,
  onFileChange,
  onTagsUpdate,
  className,
  side = "bottom",
  align = "start",
  isPreviewOverlay = false,
}: FileTagsProps) {
  const { settings } = useSettings();
  const [open, setOpen] = useState(false);
  const { tags, toggleUpdateTags, isMutating } = useUpdateTags({
    file,
    onTagsUpdate: (newTags) => {
      onTagsUpdate?.(newTags);
      onFileChange?.({ ...file, tags: newTags.join(",") });
    },
  });

  const availableTags = split(",", settings?.tags);

  if (!file?.loaded && !isPreviewOverlay) {
    return null;
  }

  const handleToggleTag = (tag: string) => {
    let nextTags: string[];
    if (tags.includes(tag)) {
      nextTags = tags.filter((t) => t !== tag);
    } else {
      nextTags = [...tags, tag];
    }
    void toggleUpdateTags(nextTags);
  };

  if (isPreviewOverlay) {
    return (
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <div
            className={cn(
              "pointer-events-auto flex max-w-[80vw] flex-wrap items-center gap-1.5 outline-none focus:outline-none focus-visible:outline-none focus-visible:ring-0",
              className,
            )}
            onClick={(e) => e.stopPropagation()}
          >
            {tags.length > 0 ? (
              tags.map((tag) => (
                <button
                  key={tag}
                  type="button"
                  className="flex items-center gap-1 rounded-full border border-white/20 bg-black/50 px-2.5 py-1 text-xs font-medium text-white shadow-lg outline-none backdrop-blur-md transition hover:bg-white/30 focus:outline-none focus-visible:outline-none focus-visible:ring-0 active:scale-95"
                >
                  <span className="text-white/70">#</span>
                  <span>{tag}</span>
                </button>
              ))
            ) : (
              <button
                type="button"
                className="flex items-center gap-1.5 rounded-full border border-dashed border-white/30 bg-black/45 px-3 py-1.5 text-xs font-medium text-white/90 shadow-xl outline-none backdrop-blur-md transition hover:bg-white/30 focus:outline-none focus-visible:outline-none focus-visible:ring-0 active:scale-95"
              >
                <Tag className="h-3.5 w-3.5" />
                <span>Bind Tag</span>
              </button>
            )}
          </div>
        </PopoverTrigger>

        <PopoverContent
          side={side}
          align={align}
          sideOffset={8}
          className="z-[140] w-64 rounded-xl border border-white/15 bg-zinc-900/95 p-2.5 text-white shadow-2xl outline-none backdrop-blur-md focus:outline-none focus-visible:outline-none focus-visible:ring-0"
          onClick={(e) => e.stopPropagation()}
          onPointerDown={(e) => e.stopPropagation()}
        >
          {isMutating && (
            <div className="absolute right-2 top-2">
              <LoaderIcon className="h-3 w-3 animate-spin text-white/60" />
            </div>
          )}

          {availableTags.length === 0 ? (
            <div className="py-2 text-center text-xs text-white/50">
              No available tags (Please configure tags in settings)
            </div>
          ) : (
            <div className="no-scrollbar flex max-h-44 flex-wrap gap-1.5 overflow-y-auto">
              {availableTags.map((tag) => {
                const isSelected = tags.includes(tag);
                return (
                  <button
                    key={tag}
                    type="button"
                    onClick={() => handleToggleTag(tag)}
                    className={cn(
                      "flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium outline-none transition focus:outline-none focus-visible:outline-none focus-visible:ring-0 active:scale-95",
                      isSelected
                        ? "bg-white font-semibold text-zinc-900 shadow"
                        : "bg-white/10 text-white/80 hover:bg-white/20 hover:text-white",
                    )}
                  >
                    {isSelected ? (
                      <Check className="h-3 w-3" />
                    ) : (
                      <Plus className="h-3 w-3 text-white/50" />
                    )}
                    <span>{tag}</span>
                  </button>
                );
              })}
            </div>
          )}
        </PopoverContent>
      </Popover>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <div
          className={cn(
            "no-scrollbar flex w-fit max-w-28 cursor-pointer items-center space-x-1 overflow-y-auto text-nowrap rounded-md bg-accent px-1.5 py-1 text-left text-sm shadow outline-none transition hover:bg-accent/80 focus:outline-none focus-visible:outline-none focus-visible:ring-0",
            tags.length === 0 && "justify-center",
            className,
          )}
          onClick={(e) => e.stopPropagation()}
        >
          <Tag className="h-3 w-3 flex-shrink-0" />
          {isMutating ? (
            <LoaderIcon className="h-3 w-3 animate-spin text-gray-500 dark:text-gray-400" />
          ) : (
            tags.length > 0 && (
              <span className="text-xs font-medium text-gray-600 dark:text-gray-200">
                {tags.join(",")}
              </span>
            )
          )}
        </div>
      </PopoverTrigger>
      <PopoverContent
        side={side}
        align={align}
        sideOffset={4}
        className="z-[140] w-64 rounded-xl border border-white/15 bg-zinc-900/95 p-2.5 text-white shadow-2xl outline-none backdrop-blur-md focus:outline-none focus-visible:outline-none focus-visible:ring-0"
        onClick={(e) => e.stopPropagation()}
        onPointerDown={(e) => e.stopPropagation()}
      >
        {isMutating && (
          <div className="absolute right-2 top-2">
            <LoaderIcon className="h-3 w-3 animate-spin text-white/60" />
          </div>
        )}
        {availableTags.length === 0 ? (
          <div className="py-2 text-center text-xs text-white/50">
            No available tags (Please configure tags in settings)
          </div>
        ) : (
          <div className="no-scrollbar flex max-h-44 flex-wrap gap-1.5 overflow-y-auto">
            {availableTags.map((tag) => {
              const isSelected = tags.includes(tag);
              return (
                <button
                  key={tag}
                  type="button"
                  onClick={() => handleToggleTag(tag)}
                  className={cn(
                    "flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium outline-none transition focus:outline-none focus-visible:outline-none focus-visible:ring-0 active:scale-95",
                    isSelected
                      ? "bg-white font-semibold text-zinc-900 shadow"
                      : "bg-white/10 text-white/80 hover:bg-white/20 hover:text-white",
                  )}
                >
                  {isSelected ? (
                    <Check className="h-3 w-3" />
                  ) : (
                    <Plus className="h-3 w-3 text-white/50" />
                  )}
                  <span>{tag}</span>
                </button>
              );
            })}
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}

interface BatchFileTagsProps {
  files: TelegramFile[];
  onTagsUpdate?: (tags: string[]) => void;
}

export function BatchFileTags({ files, onTagsUpdate }: BatchFileTagsProps) {
  const { settings } = useSettings();
  const [open, setOpen] = useState(false);
  const { tags, setTags, toggleUpdateTags, isMutating } = useBatchUpdateTags({
    files,
    onTagsUpdate: handleTagsUpdate,
  });

  const availableTags = split(",", settings?.tags);

  if (files.length === 0) {
    return null;
  }

  function handleTagsUpdate(newTags: string[]) {
    onTagsUpdate?.(newTags);
  }

  const handleToggleTag = async (tag: string) => {
    let nextTags: string[];
    if (tags.includes(tag)) {
      nextTags = tags.filter((t) => t !== tag);
    } else {
      nextTags = [...tags, tag];
    }
    setTags(nextTags);
    await toggleUpdateTags(nextTags);
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button size="sm" className="focus:outline-none focus-visible:ring-0">
          <Tag className="mr-2 h-4 w-4" />
          Edit Tags
          {`(${files.length})`}
        </Button>
      </PopoverTrigger>
      <PopoverContent
        side="bottom"
        align="start"
        className="z-[140] w-72 rounded-xl border border-white/15 bg-zinc-900/95 p-3 text-white shadow-2xl outline-none backdrop-blur-md focus:outline-none focus-visible:ring-0"
      >
        <div className="mb-2 text-xs text-white/70">
          {`Batch edit tags for ${files.length} ${files.length === 1 ? "file" : "files"}`}
        </div>
        {availableTags.length === 0 ? (
          <div className="py-2 text-center text-xs text-white/50">
            No available tags
          </div>
        ) : (
          <div className="no-scrollbar flex max-h-44 flex-wrap gap-1.5 overflow-y-auto">
            {availableTags.map((tag) => {
              const isSelected = tags.includes(tag);
              return (
                <button
                  key={tag}
                  type="button"
                  onClick={() => void handleToggleTag(tag)}
                  className={cn(
                    "flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium outline-none transition focus:outline-none focus-visible:ring-0 active:scale-95",
                    isSelected
                      ? "bg-white font-semibold text-zinc-900 shadow"
                      : "bg-white/10 text-white/80 hover:bg-white/20 hover:text-white",
                  )}
                >
                  {isSelected ? (
                    <Check className="h-3 w-3" />
                  ) : (
                    <Plus className="h-3 w-3 text-white/50" />
                  )}
                  <span>{tag}</span>
                </button>
              );
            })}
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
}
