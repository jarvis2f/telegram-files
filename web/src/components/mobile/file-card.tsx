import type { TelegramFile } from "@/lib/types";
import { useFileSpeed } from "@/hooks/use-file-speed";
import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import prettyBytes from "pretty-bytes";
import FileStatus from "@/components/file-status";
import FileControl from "@/components/file-control";
import React from "react";
import FileExtra from "@/components/file-extra";
import FileImage from "../file-image";
import { MobileFileTags } from "@/components/file-tags";

type FileCardProps = {
  index: number;
  className?: string;
  style?: React.CSSProperties;
  ref?: React.Ref<HTMLDivElement>;
  file: TelegramFile;
  onFileClick: () => void;
  onFileTagsClick?: () => void;
  layout: "detailed" | "gallery";
};

export function FileCard({
  index,
  className,
  style,
  ref,
  file,
  onFileClick,
  onFileTagsClick,
  layout,
}: FileCardProps) {
  const { downloadProgress } = useFileSpeed(file);
  const isGalleryLayout = layout === "gallery";
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
      onClick={onFileClick}
    >
      <CardContent className="relative z-20 max-h-[340px] w-full p-2">
        <div
          className={cn(
            "flex items-center gap-4",
            isGalleryLayout && "flex-col justify-center gap-2",
          )}
        >
          <FileImage
            file={file}
            className={cn(!isGalleryLayout && "h-16 w-16 min-w-16")}
            isGalleryLayout={isGalleryLayout}
          />
          {isGalleryLayout ? (
            <div className="w-5/6">
              <FileExtra file={file} rowHeight="s" ellipsis={true} />
            </div>
          ) : (
            <div className="flex-1 overflow-hidden">
              <FileExtra file={file} rowHeight="s" ellipsis={true} />
              <div className="flex items-center justify-between">
                <div className="flex flex-col justify-start gap-0.5">
                  <span className="text-xs text-muted-foreground">
                    {prettyBytes(file.size)} • {file.type}
                  </span>
                  <div className="flex items-center gap-1">
                    <FileStatus file={file} className="justify-start" />
                    {file.loaded && (
                      <MobileFileTags
                        tags={file.tags}
                        onClick={onFileTagsClick}
                      />
                    )}
                  </div>
                </div>

                <div
                  className="flex items-center justify-end"
                  onClick={(e) => e.stopPropagation()}
                >
                  <FileControl file={file} isMobile={true} />
                </div>
              </div>
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
