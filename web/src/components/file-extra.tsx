import {
  Captions,
  Clock,
  ClockArrowDown,
  Copy,
  FileCheck,
  Mountain,
} from "lucide-react";
import SpoiledWrapper from "@/components/spoiled-wrapper";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import React from "react";
import { type TelegramFile } from "@/lib/types";
import { type RowHeight } from "@/components/table-row-height-switch";
import { useCopyToClipboard } from "@/hooks/use-copy-to-clipboard";
import { formatDistanceToNow } from "date-fns";
import { cn } from "@/lib/utils";
import useIsMobile from "@/hooks/use-is-mobile";

interface FileExtraProps {
  file: TelegramFile;
  rowHeight: RowHeight;
  ellipsis?: boolean;
}

function FileName({ file, ellipsis }: FileExtraProps) {
  if (!file.fileName) {
    return null;
  }
  return (
    <SpoiledWrapper hasSensitiveContent={file.hasSensitiveContent}>
      <p className="flex items-center gap-2">
        <Mountain className="h-4 w-4 flex-shrink-0" />
        <span
          className={cn(
            "overflow-hidden text-nowrap rounded px-1 text-sm hover:bg-gray-100 dark:hover:bg-gray-800 lg:text-wrap",
            ellipsis && "line-clamp-1",
          )}
        >
          {file.fileName}
        </span>
      </p>
    </SpoiledWrapper>
  );
}

function FileCaption({ file, rowHeight, ellipsis }: FileExtraProps) {
  if (!file.caption) {
    return null;
  }
  return (
    <SpoiledWrapper hasSensitiveContent={file.hasSensitiveContent}>
      <Tooltip>
        <TooltipTrigger>
          <div className="flex items-center gap-2">
            <Captions className="h-4 w-4 flex-shrink-0" />
            <p
              className={cn(
                (rowHeight !== "l" || ellipsis) && "line-clamp-1",
                rowHeight === "l" && "line-clamp-6",
                "overflow-hidden text-wrap px-1 text-start text-sm",
              )}
            >
              {file.caption}
            </p>
          </div>
        </TooltipTrigger>
        <TooltipContent>
          <p
            className="no-scrollbar max-h-96 max-w-80 overflow-auto text-wrap rounded p-2"
            dangerouslySetInnerHTML={{
              __html: file.caption.replaceAll("\n", "<br />"),
            }}
          ></p>
        </TooltipContent>
      </Tooltip>
    </SpoiledWrapper>
  );
}

function FilePath({ file, ellipsis, rowHeight }: FileExtraProps) {
  const [, copyToClipboard] = useCopyToClipboard();
  const isMobile = useIsMobile();

  if (!file.localPath) {
    return null;
  }
  return (
    <Tooltip>
      <TooltipTrigger>
        <div className="flex items-center gap-2 text-sm">
          <FileCheck className="h-4 w-4 flex-shrink-0" />
          <p
            className={cn(
              "group cursor-pointer overflow-hidden text-nowrap rounded px-1 text-left hover:bg-gray-100 dark:hover:bg-gray-800",
              rowHeight === "l" && "text-wrap",
              ellipsis && "line-clamp-1",
              isMobile && "px-0",
            )}
            onClick={() => !isMobile && copyToClipboard(file.localPath)}
          >
            {file.localPath.split("/").pop()}
            <Copy className="ml-1 inline-flex h-4 w-4 opacity-0 group-hover:opacity-100" />
          </p>
        </div>
      </TooltipTrigger>
      <TooltipContent>
        <div className="max-w-80 overflow-y-scroll text-wrap rounded">
          {file.localPath}
        </div>
      </TooltipContent>
    </Tooltip>
  );
}

function FileTime({ file }: FileExtraProps) {
  const isMobile = useIsMobile();
  return (
    <div className="flex items-center gap-2">
      <Tooltip>
        <TooltipTrigger asChild>
          <p className="flex items-center gap-2">
            <Clock className="h-4 w-4 flex-shrink-0" />
            <span className="rounded px-1 text-sm hover:bg-gray-100 dark:hover:bg-gray-800">
              {formatDistanceToNow(new Date(file.date * 1000), {
                addSuffix: true,
              })}
            </span>
          </p>
        </TooltipTrigger>
        <TooltipContent>
          <div className="max-w-80 text-wrap rounded p-2">
            {`Message received at ${new Date(file.date * 1000).toLocaleString()}`}
          </div>
        </TooltipContent>
      </Tooltip>
      {!isMobile && file.completionDate && (
        <Tooltip>
          <TooltipTrigger asChild>
            <p className="hidden items-center gap-2 lg:flex">
              <ClockArrowDown className="h-4 w-4" />
              <span className="rounded px-1 text-sm hover:bg-gray-100 dark:hover:bg-gray-800">
                {formatDistanceToNow(new Date(file.completionDate), {
                  addSuffix: true,
                })}
              </span>
            </p>
          </TooltipTrigger>
          <TooltipContent>
            <div className="max-w-80 text-wrap rounded p-2">
              {`File downloaded at ${new Date(file.completionDate).toLocaleString()}`}
            </div>
          </TooltipContent>
        </Tooltip>
      )}
    </div>
  );
}

function hasValue(value: string | null | undefined): boolean {
  return value !== null && value !== undefined && value.trim() !== "";
}

export default function FileExtra(fileExtraProps: FileExtraProps) {
  const { file, rowHeight } = fileExtraProps;
  if (rowHeight === "s") {
    const renderOrder = [
      { key: "fileName", Component: FileName },
      { key: "caption", Component: FileCaption },
      { key: "localPath", Component: FilePath },
      { key: "default", Component: FileTime },
    ];

    for (const item of renderOrder) {
      if (
        item.key === "default" ||
        hasValue(file[item.key as keyof typeof file] as string)
      ) {
        return (
          <TooltipProvider>
            <item.Component {...fileExtraProps} />
          </TooltipProvider>
        );
      }
    }
  }

  return (
    <div className="relative flex flex-col space-y-1 overflow-hidden">
      <TooltipProvider>
        <FileName {...fileExtraProps} />
        <FileCaption {...fileExtraProps} />
        <FilePath {...fileExtraProps} />
        <FileTime {...fileExtraProps} />
      </TooltipProvider>
    </div>
  );
}
