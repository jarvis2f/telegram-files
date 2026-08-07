"use client";
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { SquareChevronLeft, WandSparkles } from "lucide-react";
import { useFiles } from "@/hooks/use-files";
import {
  getRowHeightPX,
  TableRowHeightSwitch,
  useRowHeightLocalStorage,
} from "@/components/table-row-height-switch";
import TableColumnFilter, {
  type Column,
} from "@/components/table-column-filter";
import { cn } from "@/lib/utils";
import FileNotFount from "@/components/file-not-found";
import FileRow from "@/components/file-row";
import { useVirtualizer } from "@tanstack/react-virtual";
import { type TelegramFile } from "@/lib/types";
import { isEqual } from "lodash";
import FileViewer from "@/components/file-viewer";
import FileFilters from "./file-filters";
import { Badge } from "@/components/ui/badge";
import FileBatchControl from "@/components/file-batch-control";
import { useLocalStorage } from "@/hooks/use-local-storage";
import {
  applyColumnPreferences,
  type ColumnPreference,
} from "@/lib/column-preferences";
import { useShareEnabled } from "@/hooks/use-share-enabled";
import { TooltipWrapper } from "@/components/ui/tooltip";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";

const COLUMNS: Column[] = [
  {
    id: "content",
    label: "Content",
    isVisible: true,
    className: "text-center",
  },
  { id: "type", label: "Type", isVisible: true, className: "w-16 text-center" },
  {
    id: "size",
    label: "Size",
    isVisible: true,
    className: "w-20 text-center",
  },
  {
    id: "source",
    label: "Source",
    isVisible: true,
    className: "w-20 text-center",
    tooltip: "The file's original source and how it was downloaded",
  },
  {
    id: "sharing",
    label: "Sharing",
    isVisible: true,
    className: "w-20 text-center",
    tooltip: "The file's resource sharing and publication status",
  },
  {
    id: "seeding",
    label: "Seeding",
    isVisible: true,
    className: "w-40 text-center",
    tooltip: "qBittorrent seeding status, traffic speeds, share ratio and uploaded total",
  },
  {
    id: "status",
    label: "Status",
    isVisible: true,
    className: "w-32 text-center",
  },
  {
    id: "tags",
    label: "Tags",
    isVisible: false,
    className: "w-32",
  },
  {
    id: "extra",
    label: "Extra",
    isVisible: true,
    className: "flex-1 max-w-44 overflow-hidden lg:max-w-none",
  },
  {
    id: "actions",
    label: "Actions",
    isVisible: true,
    className: "w-36 min-w-36 text-center",
  },
];

interface FileTableProps {
  accountId: string;
  chatId: string;
  messageThreadId?: number;
  link?: string;
}

export function FileTable({
  accountId,
  chatId,
  messageThreadId,
  link,
}: FileTableProps) {
  const [selectedFiles, setSelectedFiles] = useState<Set<number>>(new Set());
  const tableParentRef = useRef<HTMLDivElement>(null);
  const shareEnabled = useShareEnabled();
  const defaultColumns = useMemo(() => {
    return COLUMNS.map((col) => {
      if (col.id === "source" || col.id === "sharing" || col.id === "seeding") {
        return { ...col, isVisible: shareEnabled };
      }
      return col;
    });
  }, [shareEnabled]);

  const [columnPreferences, setColumnPreferences] = useLocalStorage<
    ColumnPreference[]
  >("telegramFileListColumns", []);
  const columns = useMemo(
    () => applyColumnPreferences(defaultColumns, columnPreferences),
    [defaultColumns, columnPreferences],
  );
  const [rowHeight, setRowHeight] = useRowHeightLocalStorage(
    "telegramFileList",
    "m",
  );
  const useFilesProps = useFiles(accountId, chatId, messageThreadId, link);
  const {
    filters,
    updateField,
    handleFilterChange,
    clearFilters,
    isLoading,
    size,
    files,
    handleLoadMore,
    loadedCount,
    totalCount,
  } = useFilesProps;
  const [currentViewFile, setCurrentViewFile] = useState<
    TelegramFile | undefined
  >();
  const [viewerOpen, setViewerOpen] = useState(false);
  const rowVirtual = useVirtualizer({
    useFlushSync: false,
    count: files.length,
    getScrollElement: () => tableParentRef.current,
    estimateSize: (index) => {
      const file = files[index]!;
      const height = getRowHeightPX(rowHeight);

      if (
        file.downloadStatus === "idle" ||
        file.downloadStatus === "completed" ||
        file.size === 0
      ) {
        return height;
      }
      return height + 8;
    },
    paddingStart: 1,
    paddingEnd: 1,
  });
  const virtualItems = rowVirtual.getVirtualItems();

  useEffect(() => {
    rowVirtual.measure();
  }, [rowHeight, rowVirtual]);

  const maybeLoadMore = useCallback(() => {
    const element = tableParentRef.current;
    if (!element) {
      return;
    }

    const distanceFromBottom =
      element.scrollHeight - element.scrollTop - element.clientHeight;
    const preloadDistance = Math.max(getRowHeightPX(rowHeight) * 6, 480);
    if (distanceFromBottom <= preloadDistance) {
      void handleLoadMore();
    }
  }, [handleLoadMore, rowHeight]);

  useEffect(() => {
    const frame = window.requestAnimationFrame(maybeLoadMore);
    return () => window.cancelAnimationFrame(frame);
  }, [files.length, isLoading, maybeLoadMore]);

  useEffect(() => {
    const lastItem = virtualItems[virtualItems.length - 1];
    if (!lastItem || files.length === 0) {
      return;
    }

    if (lastItem.index >= files.length - 8) {
      void handleLoadMore();
    }
  }, [files.length, handleLoadMore, virtualItems]);

  useEffect(() => {
    if (files.length === 0 || !currentViewFile) {
      return;
    }
    const index = files.findIndex(
      (f) => f.id === currentViewFile.id || f.uniqueId === currentViewFile.uniqueId,
    );
    if (index === -1) {
      setCurrentViewFile(undefined);
      return;
    }
    const file = files[index]!;
    if (!isEqual(file, currentViewFile)) {
      setCurrentViewFile(file);
    }
  }, [currentViewFile, files]);

  const dynamicClass = useMemo(() => {
    switch (rowHeight) {
      case "s":
        return {
          content: "h-6 w-6",
          contentCell: "w-16",
        };
      case "m":
        return {
          content: "h-20 w-20",
          contentCell: "w-24",
        };
      case "l":
        return {
          content: "h-60 w-60",
          contentCell: "w-64",
        };
    }
  }, [rowHeight]);

  const handleSelectAll = () => {
    if (selectedFiles.size === files.length) {
      setSelectedFiles(new Set());
    } else {
      setSelectedFiles(new Set(files.map((file) => file.id)));
    }
  };

  const handleSelectFile = (fileId: number) => {
    const newSelected = new Set(selectedFiles);
    if (newSelected.has(fileId)) {
      newSelected.delete(fileId);
    } else {
      newSelected.add(fileId);
    }
    setSelectedFiles(newSelected);
  };

  return (
    <>
      <div className="mb-6 flex flex-col flex-wrap justify-between gap-2 md:flex-row">
        <div className="flex items-center gap-3">
          {messageThreadId && (
            <Button
              variant="link"
              onClick={() => {
                window.history.back();
              }}
            >
              <SquareChevronLeft className="h-4 w-4" />
              Back
            </Button>
          )}
          {link ? (
            <Badge variant="outline" className="flex h-full bg-accent">
              <WandSparkles className="mr-2 h-4 w-4" />
              {link}
            </Badge>
          ) : (
            <>
              <Badge variant="outline" className="flex h-full bg-accent">
                {filters.type.charAt(0).toUpperCase() + filters.type.slice(1)}
              </Badge>
              <FileFilters
                telegramId={accountId}
                chatId={chatId}
                filters={filters}
                onFiltersChange={handleFilterChange}
                clearFilters={clearFilters}
              />
            </>
          )}
        </div>
        <div className="hidden items-center gap-4 md:flex">
          <span className="text-sm text-muted-foreground">
            Loaded {loadedCount}
            {totalCount === undefined ? "" : ` / ${totalCount}`}
          </span>
          <TableColumnFilter
            columns={columns}
            onColumnConfigChange={(nextColumns) => {
              setColumnPreferences(
                nextColumns.map(({ id, isVisible }) => ({ id, isVisible })),
              );
            }}
          />
          <TableRowHeightSwitch
            rowHeight={rowHeight}
            setRowHeightAction={setRowHeight}
          />
        </div>
      </div>
      {currentViewFile && (
        <FileViewer
          open={viewerOpen}
          onOpenChange={setViewerOpen}
          file={currentViewFile}
          onFileChange={(newFile) => {
            setCurrentViewFile(newFile);
            void updateField(newFile.uniqueId, { tags: newFile.tags });
          }}
          {...useFilesProps}
        />
      )}
      <div className="h-[calc(100vh-13rem)] space-y-4 overflow-hidden">
        <FileBatchControl
          files={files}
          selectedFiles={selectedFiles}
          setSelectedFiles={setSelectedFiles}
          updateField={updateField}
        />

        <div
          className="no-scrollbar relative h-full overflow-auto rounded-md border"
          ref={tableParentRef}
          onScroll={maybeLoadMore}
        >
          <div className="sticky top-0 z-20 flex h-10 items-center border-b bg-background/90 text-sm text-muted-foreground backdrop-blur-sm">
            <div className="w-[30px] text-center">
              <Checkbox
                checked={selectedFiles.size === files.length}
                onCheckedChange={handleSelectAll}
              />
            </div>
            {columns.map((col) =>
              col.isVisible ? (
                <div
                  key={col.id}
                  suppressHydrationWarning
                  className={cn(
                    col.className ?? "",
                    col.id === "content" ? dynamicClass.contentCell : "",
                  )}
                >
                  {col.tooltip ? (
                    <TooltipWrapper content={col.tooltip}>
                      <span className="cursor-help border-b border-dotted border-muted-foreground/60">
                        {col.label}
                      </span>
                    </TooltipWrapper>
                  ) : (
                    col.label
                  )}
                </div>
              ) : null,
            )}
          </div>
          {size === 1 && isLoading && (
            <div className="sticky left-1/2 top-0 z-10 flex h-full w-full items-center justify-center bg-accent">
              <DotmTriangle2
                size={32}
                dotSize={4}
                speed={1.4}
                opacityBase={0.1}
                opacityMid={0.4}
                opacityPeak={0.95}
                ariaLabel="Loading files"
              />
            </div>
          )}
          <div className="h-full">
            <div
              className={cn("relative w-full")}
              style={{ height: `${rowVirtual.getTotalSize()}px` }}
            >
              {files.length !== 0 &&
                virtualItems.map((virtualRow) => {
                  const file = files[virtualRow.index]!;
                  return (
                    <FileRow
                      index={virtualRow.index}
                      className={cn(
                        "absolute left-0 top-0 flex w-full items-center",
                      )}
                      style={{
                        height: `${virtualRow.size}px`,
                        transform: `translateY(${virtualRow.start}px)`,
                      }}
                      ref={rowVirtual.measureElement}
                      file={file}
                      updateField={updateField}
                      checked={selectedFiles.has(file.id)}
                      onCheckedChange={() => handleSelectFile(file.id)}
                      onFileClick={() => {
                        setCurrentViewFile(file);
                        setViewerOpen(true);
                      }}
                      properties={{
                        rowHeight: rowHeight,
                        dynamicClass,
                        columns,
                      }}
                      key={`${file.messageId}-${file.uniqueId}-${virtualRow.index}`}
                    />
                  );
                })}
            </div>
            {!isLoading && files.length === 0 && <FileNotFount />}
          </div>
        </div>
      </div>
    </>
  );
}
