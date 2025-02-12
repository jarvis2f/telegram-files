import type { TelegramFile } from "@/lib/types";
import { useFileSpeed } from "@/hooks/use-file-speed";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import FileAvatar from "@/components/file-avatar";
import prettyBytes from "pretty-bytes";
import FileStatus from "@/components/file-status";
import FileControl from "@/components/file-control";
import React, { useState } from "react";
import { type useFiles } from "@/hooks/use-files";
import FileDrawer from "@/components/mobile/file-drawer";

type FileCardProps = {
  index: number;
  className?: string;
  style?: React.CSSProperties;
  ref?: React.Ref<HTMLDivElement>;
  file: TelegramFile;
} & ReturnType<typeof useFiles>;

export function FileCard({
  index,
  className,
  style,
  ref,
  file,
  ...useFilesProps
}: FileCardProps) {
  const { downloadProgress } = useFileSpeed(file);
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  return (
    <Card
      ref={ref}
      data-index={index}
      className={cn(
        "before:ease-[cubic-bezier(0.4, 0, 0.2, 1)] before:will-change:transform relative before:absolute before:inset-0 before:bottom-0 before:left-0 before:top-auto before:z-10 before:h-2 before:transform before:rounded-bl-xl before:bg-primary before:duration-500 before:content-['']",
        downloadProgress > 0 && downloadProgress !== 100
          ? `before:w-progress`
          : "before:w-0",
        className,
      )}
      style={{
        // eslint-disable-next-line @typescript-eslint/ban-ts-comment
        // @ts-expect-error
        "--tw-progress-width": `${downloadProgress > 0 && downloadProgress !== 100 ? downloadProgress.toFixed(0) + "%" : "0"}`,
        ...style,
      }}
      onClick={() => setIsDrawerOpen(true)}
    >
      <FileDrawer
        open={isDrawerOpen}
        onOpenChange={setIsDrawerOpen}
        file={file}
        {...useFilesProps}
      />
      <CardContent className="relative z-20 w-full p-2">
        <div className="flex items-center gap-4">
          <FileAvatar file={file} className="h-16 w-16 min-w-16" />
          <div className="flex-1 overflow-hidden">
            {file.fileName && (
              <h3 className="truncate font-medium">{file.fileName}</h3>
            )}
            <div className="flex items-center justify-between">
              <div className="flex flex-col justify-start gap-0.5">
                <span className="text-xs text-muted-foreground">
                  {prettyBytes(file.size)} • {file.type}
                </span>
                <FileStatus file={file} className="justify-start" />
              </div>

              <div
                className="flex items-center justify-end"
                onClick={(e) => e.stopPropagation()}
              >
                <FileControl file={file} isMobile={true} />
              </div>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
