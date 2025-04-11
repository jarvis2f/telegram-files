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
            onChange={setTags}
            tags={split(",", settings?.tags)}
          />
        </HoverCardContent>
      </HoverCardPrimitive.Portal>
    </HoverCard>
  );
}

export function MobileFileTags({
  tags,
  onClick,
}: {
  tags?: string;
  onClick?: () => void;
}) {
  const localTags = split(",", tags);
  return (
    <div
      className={cn(
        "no-scrollbar flex h-6 min-w-6 max-w-20 cursor-pointer items-center space-x-1 overflow-y-scroll text-nowrap rounded-md bg-accent px-1 py-1",
        localTags.length === 0 && "justify-center",
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
      <DrawerContent className="w-full max-w-sm" aria-describedby={undefined}>
        <div className="mx-auto mt-4 h-2 w-[100px] rounded-full bg-muted" />

        <div className="p-4">
          <DrawerTitle className="mb-4">Edit Tags</DrawerTitle>
          <TagsSelector
            value={tags}
            onChange={setTags}
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
