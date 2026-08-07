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
import { TooltipWrapper } from "@/components/ui/tooltip";
import { Badge } from "@/components/ui/badge";
import { FileSharing, FileSource } from "@/components/file-provenance";
import { Progress } from "@/components/ui/progress";
import FileTags from "@/components/file-tags";

type FileCardProps = {
  index: number;
  className?: string;
  style?: React.CSSProperties;
  ref?: React.Ref<HTMLDivElement>;
  file: TelegramFile;
  onFileClick: () => void;
  onFileChange?: (file: TelegramFile) => void;
  layout: "detailed" | "gallery";
};

export function FileCard({
  index,
  className,
  style,
  ref,
  file,
  onFileClick,
  onFileChange,
  layout,
}: FileCardProps) {
  const { downloadProgress } = useFileSpeed(file);
  const isGalleryLayout = layout === "gallery";
  return (
    <Card
      ref={ref}
      data-index={index}
      className={cn(
        "relative overflow-hidden",
        isGalleryLayout
          ? "rounded-xl border-white/10 bg-zinc-950 text-white shadow-sm active:scale-[0.99]"
          : "",
        className,
      )}
      style={style}
      onClick={onFileClick}
    >
      <CardContent
        className={cn(
          "relative z-20 w-full",
          isGalleryLayout ? "h-full p-0" : "max-h-[340px] p-2",
        )}
      >
        <div
          className={cn(
            "flex items-center gap-4",
            isGalleryLayout && "relative h-full flex-col justify-center gap-0",
          )}
        >
          {file.reactionCount > 0 && (
            <TooltipWrapper content="Reaction Count">
              <Badge
                className={cn(
                  "absolute z-30 flex h-6 w-6 items-center justify-center rounded-full bg-blue-500 text-xs hover:bg-blue-600",
                  isGalleryLayout ? "left-2 top-2" : "-left-1 -top-1",
                )}
              >
                {file.reactionCount}
              </Badge>
            </TooltipWrapper>
          )}
          <FileImage
            file={file}
            className={cn(
              isGalleryLayout
                ? "h-full max-h-none w-full rounded-none object-cover"
                : "h-16 w-16 min-w-16",
            )}
            isGalleryLayout={isGalleryLayout}
          />
          {isGalleryLayout ? (
            <div className="absolute inset-x-0 bottom-0 z-20 bg-gradient-to-t from-black/85 via-black/45 to-transparent px-3 pb-3 pt-12">
              <div className="min-w-0 text-white">
                <FileExtra file={file} rowHeight="s" ellipsis={true} />
              </div>
              <div className="mt-2 flex items-end justify-between gap-3">
                <div className="min-w-0 space-y-1">
                  <span className="block truncate text-[11px] font-medium text-white/75">
                    {prettyBytes(file.size)} • {file.type}
                  </span>
                  <div className="flex min-h-5 flex-wrap items-center gap-1.5">
                    <FileStatus file={file} className="justify-start" />
                    {file.loaded && (
                      <FileTags
                        file={file}
                        onFileChange={onFileChange}
                        className="bg-foreground"
                      />
                    )}
                  </div>
                </div>
                <div className="shrink-0" onClick={(e) => e.stopPropagation()}>
                  <FileControl file={file} isMobile={true} />
                </div>
              </div>
            </div>
          ) : (
            <div className="flex-1 overflow-hidden">
              <FileExtra file={file} rowHeight="s" ellipsis={true} />
              <div className="flex items-center justify-between">
                <div className="flex flex-col justify-start gap-0.5">
                  <span className="text-xs text-muted-foreground">
                    {prettyBytes(file.size)} • {file.type}
                  </span>
                  <div className="flex min-h-5 items-center gap-2">
                    <FileSource
                      file={file}
                      className="justify-start"
                      showEmpty={false}
                    />
                    <FileSharing file={file} showEmpty={false} />
                  </div>
                  <div className="flex items-center gap-1">
                    <FileStatus file={file} className="justify-start" />
                    {file.loaded && (
                      <FileTags
                        file={file}
                        onFileChange={onFileChange}
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
      {downloadProgress > 0 && downloadProgress !== 100 && (
        <div className="absolute inset-x-2 bottom-1 z-30">
          <Progress
            value={downloadProgress}
            variant="download"
            data-download-state={file.downloadStatus}
          />
        </div>
      )}
    </Card>
  );
}
