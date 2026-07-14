import { type TelegramFile } from "@/lib/types";
import { LoaderIcon, Tag } from "lucide-react";
import React, { useEffect, useState } from "react";
import { TagsSelector } from "@/components/ui/tags-selector";
import { cn, split } from "@/lib/utils";
import { useSettings } from "@/hooks/use-settings";
import useSWRMutation from "swr/mutation";
import { POST } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import { useDebounce } from "use-debounce";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "./ui/hover-card";
import * as HoverCardPrimitive from "@radix-ui/react-hover-card";
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerFooter,
  DrawerTitle,
} from "@/components/ui/drawer";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";

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
        toast({
          variant: "success",
          description: "Tags updated successfully",
        });
      },
    },
  );

  const toggleUpdateTags = async () => {
    await trigger({
      files: files.map((file) => ({
        telegramId: file.telegramId ?? 0,
        chatId: file.chatId ?? 0,
        messageId: file.messageId ?? 0,
        uniqueId: file.uniqueId ?? "",
        fileId: file.id ?? 0,
      })),
      tags: tags.join(","),
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

function useUpdateTags({
  file,
  onTagsUpdate,
}: {
  file: TelegramFile;
  onTagsUpdate?: (tags: string[]) => void;
}) {
  const [tags, setTags] = useState<string[]>(split(",", file?.tags));

  useEffect(() => {
    setTags(split(",", file.tags));
  }, [file?.tags]);

  const { trigger, isMutating } = useSWRMutation(
    `/file/${file.uniqueId}/update-tags`,
    (key, { arg }: { arg: { tags: string } }) => POST(key, arg),
    {
      onSuccess: () => {
        onTagsUpdate?.(tags);
        toast({
          variant: "success",
          description: "Tags updated successfully",
        });
      },
    },
  );

  const toggleUpdateTags = async () => {
    if ((!file.tags || file.tags.length === 0) && tags.length === 0) {
      return;
    }
    const newTags = tags.join(",");
    if (newTags === file.tags) {
      return;
    }
    await trigger({ tags: newTags });
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
  onTagsUpdate?: (tags: string[]) => void;
}

export default function FileTags({ file, onTagsUpdate }: FileTagsProps) {
  const { settings } = useSettings();
  const [open, setOpen] = useState(false);
  const { tags, setTags, toggleUpdateTags, isMutating } = useUpdateTags({
    file,
    onTagsUpdate,
  });

  if (!file.loaded) {
    return null;
  }

  function handleOpenChange(open: boolean) {
    setOpen(open);
    if (!open) {
      void toggleUpdateTags();
    }
  }

  return (
    <HoverCard
      open={open}
      onOpenChange={handleOpenChange}
      openDelay={300}
      closeDelay={100}
    >
      <HoverCardTrigger>
        <div className="no-scrollbar flex w-fit max-w-28 cursor-pointer items-center space-x-1 overflow-y-scroll text-nowrap rounded-md bg-accent px-1 py-1 text-left text-sm shadow">
          <Tag className="h-3 w-3 flex-shrink-0" />
          {isMutating ? (
            <LoaderIcon className="h-3 w-3 animate-spin text-gray-500 dark:text-gray-400" />
          ) : (
            file.tags &&
            file.tags.length > 0 && (
              <span className="text-xs font-medium text-gray-600 dark:text-gray-200">
                {file.tags}
              </span>
            )
          )}
        </div>
      </HoverCardTrigger>
      <HoverCardPrimitive.Portal>
        <HoverCardContent className="w-80" side="right">
          <TagsSelector
            value={tags}
            onChangeAction={setTags}
            tags={split(",", settings?.tags)}
          />
        </HoverCardContent>
      </HoverCardPrimitive.Portal>
    </HoverCard>
  );
}

export function MobileFileTags({
  className,
  tags,
  onClick,
}: {
  className?: string;
  tags?: string;
  onClick?: () => void;
}) {
  const localTags = split(",", tags);
  return (
    <div
      className={cn(
        "no-scrollbar flex h-6 min-w-6 max-w-20 cursor-pointer items-center space-x-1 overflow-y-scroll text-nowrap rounded-md bg-accent px-1 py-1",
        localTags.length === 0 && "justify-center",
        className,
      )}
      onClick={(e) => {
        e.stopPropagation();
        onClick?.();
      }}
    >
      <Tag className="h-3 w-3 flex-shrink-0" />
      {localTags.length > 0 && (
        <span className="text-xs font-medium text-gray-600 dark:text-gray-200">
          {localTags[0]}
        </span>
      )}
    </div>
  );
}

interface MobileFileTagsDrawerProps {
  file: TelegramFile;
  onTagsUpdate?: (tags: string[]) => void;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export function MobileFileTagsDrawer({
  file,
  onTagsUpdate,
  open,
  onOpenChange,
}: MobileFileTagsDrawerProps) {
  const { settings } = useSettings();
  const { tags, setTags, toggleUpdateTags, isMutating } = useUpdateTags({
    file,
    onTagsUpdate,
  });

  if (!file.loaded) {
    return null;
  }

  return (
    <Drawer open={open} onOpenChange={onOpenChange} disablePreventScroll={true}>
      <DrawerContent
        className="w-full"
        aria-describedby={undefined}
        onTouchStart={(e) => {
          e.preventDefault();
          e.stopPropagation();
        }}
      >
        <div className="p-4">
          <DrawerTitle className="mb-4">Edit Tags</DrawerTitle>
          <TagsSelector
            value={tags}
            onChangeAction={setTags}
            tags={split(",", settings?.tags)}
          />
        </div>

        <DrawerFooter>
          <Button
            onClick={() => {
              void toggleUpdateTags();
              onOpenChange(false);
            }}
          >
            {isMutating ? (
              <LoaderIcon className="h-4 w-4 animate-spin text-gray-500 dark:text-gray-400" />
            ) : (
              "Submit"
            )}
          </Button>
          <DrawerClose asChild>
            <Button variant="outline">Close</Button>
          </DrawerClose>
        </DrawerFooter>
      </DrawerContent>
    </Drawer>
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

  if (files.length === 0) {
    return null;
  }

  function handleTagsUpdate(newTags: string[]) {
    onTagsUpdate?.(newTags);
    if (open) {
      setOpen(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm">
          <Tag className="mr-2 h-4 w-4" />
          Edit Tags
          {`(${files.length})`}
        </Button>
      </DialogTrigger>
      <DialogContent className="w-full max-w-md">
        <DialogTitle>Edit Tags</DialogTitle>
        <DialogDescription>
          {`You can edit tags for ${files.length} files at once. This will update the
            tags for all selected files.`}
        </DialogDescription>
        <TagsSelector
          value={tags}
          onChangeAction={setTags}
          tags={split(",", settings?.tags)}
        />
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Close</Button>
          </DialogClose>
          <Button onClick={() => toggleUpdateTags()}>
            {isMutating ? (
              <LoaderIcon className="h-4 w-4 animate-spin text-gray-500 dark:text-gray-400" />
            ) : (
              "Submit"
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
