"use client";

import React from "react";
import { type TelegramFile } from "@/lib/types";
import { cn } from "@/lib/utils";
import FileTags from "@/components/file-tags";

interface MobilePreviewTagOverlayProps {
  file: TelegramFile;
  onFileChange?: (file: TelegramFile) => void;
  className?: string;
  bottomOffset?: string;
}

export function MobilePreviewTagOverlay({
  file,
  onFileChange,
  className,
  bottomOffset = "bottom-28",
}: MobilePreviewTagOverlayProps) {
  return (
    <div
      className={cn(
        "pointer-events-none absolute inset-0 z-30 flex flex-col justify-end p-4",
        className,
      )}
      onClick={(e) => e.stopPropagation()}
    >
      {/* Bottom-left Tag Selector Overlay */}
      <div
        className={cn(
          "pointer-events-auto absolute left-4 flex max-w-[80%] flex-wrap items-center gap-1.5 transition-all",
          bottomOffset,
        )}
      >
        <FileTags
          file={file}
          onFileChange={onFileChange}
          isPreviewOverlay={true}
          side="top"
          align="start"
        />
      </div>
    </div>
  );
}
