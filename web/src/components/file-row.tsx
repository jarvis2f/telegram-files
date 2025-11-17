import { cn } from "@/lib/utils";
import { type RowHeight } from "@/components/table-row-height-switch";
import { Checkbox } from "@/components/ui/checkbox";
import React, { type ReactNode, useState } from "react";
import { type TelegramFile } from "@/lib/types";
import prettyBytes from "pretty-bytes";
import { fromUnixTime, format } from "date-fns";
import FileStatus from "@/components/file-status";
import FileExtra from "@/components/file-extra";
import FileControl from "@/components/file-control";
import { type Column } from "./table-column-filter";
import { useFileSpeed } from "@/hooks/use-file-speed";
import { Progress } from "@/components/ui/progress";
import FileImage from "./file-image";
import FileTags from "@/components/file-tags";
import { Badge } from "@/components/ui/badge";
import { TooltipWrapper } from "@/components/ui/tooltip";

type FileRowProps = {
  index: number;
  className?: string;
  style?: React.CSSProperties;
  ref?: React.Ref<HTMLTableRowElement>;
  file: TelegramFile;
  checked: boolean;
  updateField: (
    uniqueId: string,
    patch: Partial<TelegramFile>,
  ) => Promise<void>;
  properties: {
    rowHeight: RowHeight;
    dynamicClass: {
      content: string;
      contentCell: string;
    };
    columns: Column[];
  };
  onCheckedChange: (checked: boolean) => void;
  onFileClick: () => void;
};

function formatDurationFromSeconds(seconds: number) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60

  const hh = h > 0 ? String(h).padStart(2, '0') + ':' : ''
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')

  return hh + mm + ':' + ss

}

export default function FileRow({
  index,
  className,
  style,
  ref,
  file,
  checked,
  updateField,
  properties,
  onCheckedChange,
  onFileClick,
}: FileRowProps) {
  const { rowHeight, dynamicClass, columns } = properties;
  const { downloadProgress, downloadSpeed } = useFileSpeed(file);
  const [hovered, setHovered] = useState(false);

  const columnRenders: Record<string, ReactNode> = {
    content: (
      <div
        className="relative flex cursor-pointer items-center justify-center gap-2"
        onClick={onFileClick}
      >
        {file.reactionCount > 0 && (
          <TooltipWrapper content="Reaction Count">
            <Badge className="absolute -left-1 -top-1.5 z-10 flex h-6 w-6 items-center justify-center overflow-hidden rounded-full bg-blue-500 text-sm hover:bg-blue-600">
              {file.reactionCount}
            </Badge>
          </TooltipWrapper>
        )}
        <FileImage file={file} className={dynamicClass.content} />
      </div>
    ),
    type: (
      <div className="flex flex-col items-center">
        <span className="text-sm capitalize">{file.type}</span>
        {process.env.NODE_ENV === "development" && (
          <div className="flex flex-col items-center text-xs">
            <span className="text-xs">{file.id}</span>
            <span className="text-xs">{file.messageId}</span>
          </div>
        )}
      </div>
    ),
    size: (
      <>
        <span className="text-sm">{prettyBytes(file.size)}</span>
        <br />
        {file.extra && "duration" in file.extra ? (
          <span className="text-sm">
            {formatDurationFromSeconds(file.extra?.duration)}
          </span>
        ) : (
          ""
        )}
      </>
    ),
    status: <FileStatus file={file} />,
    formatedDate: (
      <span>{format(fromUnixTime(file.date), "yyyy-MM-dd HH:mm:ss")}</span>
    ),
    tags: (
      <FileTags
        file={file}
        onTagsUpdate={(tags) =>
          updateField(file.uniqueId, { tags: tags.join(",") })
        }
      />
    ),
    extra: <FileExtra file={file} rowHeight={rowHeight} />,
    actions: (
      <FileControl
        file={file}
        downloadSpeed={downloadSpeed}
        hovered={hovered}
      />
    ),
  };

  return (
    <div
      data-index={index}
      className={cn("flex w-full flex-col border-b", className)}
      style={style}
      ref={ref}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div className="flex w-full flex-1 items-center hover:bg-accent">
        <div className="w-[30px] text-center">
          <Checkbox checked={checked} onCheckedChange={onCheckedChange} />
        </div>
        {columns.map((col) =>
          col.isVisible ? (
            <div
              key={col.id}
              className={cn(
                col.className ?? "",
                col.id === "content" ? dynamicClass.contentCell : "",
              )}
            >
              {columnRenders[col.id]}
            </div>
          ) : null,
        )}
      </div>
      {downloadProgress > 0 && downloadProgress !== 100 && (
        <div className="flex w-full items-end justify-between gap-2">
          <Progress
            value={downloadProgress}
            className="flex-1 rounded-none md:w-32"
          />
        </div>
      )}
    </div>
  );
}
