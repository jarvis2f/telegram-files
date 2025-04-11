"use client";
import React, { useEffect, useMemo, useRef, useState } from "react";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { Download, LoaderCircle, LoaderPinwheel } from "lucide-react";
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
import useSWRMutation from "swr/mutation";
import { POST } from "@/lib/api";
import FileRow from "@/components/file-row";
import { useVirtualizer } from "@tanstack/react-virtual";
import { type TelegramFile } from "@/lib/types";
import FileViewer from "@/components/file-viewer";
import FileFilters from "./file-filters";
import { Badge } from "@/components/ui/badge";

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
    id: "status",
    label: "Status",
    isVisible: true,
    className: "w-32 text-center",
  },
  {
    id: "tags",
    label: "Tags",
    isVisible: true,
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
    className: "text-center w-40 min-w-40",
  },
];

interface FileTableProps {
  accountId: string;
  chatId: string;
}

export function FileTable({ accountId, chatId }: FileTableProps) {
  const [selectedFiles, setSelectedFiles] = useState<Set<number>>(new Set());
  const tableParentRef = useRef(null);
  const [columns, setColumns] = useState<Column[]>(COLUMNS);
  const [rowHeight, setRowHeight] = useRowHeightLocalStorage(
    "telegramFileList",
    "m",
  );
  const useFilesProps = useFiles(accountId, chatId);
  const {
    filters,
    updateField,
    handleFilterChange,
    clearFilters,
    isLoading,
    size,
    files,
    handleLoadMore,
  } = useFilesProps;
  const [currentViewFile, setCurrentViewFile] = useState<
    TelegramFile | undefined
  >();
  const [viewerOpen, setViewerOpen] = useState(false);
  const {
    trigger: startDownloadMultiple,
    isMutating: startDownloadMultipleMutating,
  } = useSWRMutation(
    "/files/start-download-multiple",
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
            fileId: number;
          }>;
        };
      },
    ) => POST(key, arg),
    {
      onSuccess: () => {
        setSelectedFiles(new Set());
      },
    },
  );
  const rowVirtual = useVirtualizer({
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

  useEffect(() => {
    rowVirtual.measure();
  }, [rowHeight, rowVirtual]);

  useEffect(() => {
    const [lastItem] = [...rowVirtual.getVirtualItems()].reverse();
    if (!lastItem) {
      return;
    }

    if (lastItem.index >= files.length - 1) {
      void handleLoadMore();
    }
    //eslint-disable-next-line
  }, [files.length, handleLoadMore, rowVirtual.getVirtualItems()]);

  useEffect(() => {
    if (files.length === 0 || !currentViewFile) {
      return;
    }
    const index = files.findIndex((f) => f.id === currentViewFile.id);
    if (index === -1) {
      setCurrentViewFile(undefined);
      return;
    }
    const file = files[index]!;
    if (currentViewFile.next === undefined && file.next !== undefined) {
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
      setSelectedFiles(
        new Set(
          files
            .filter((file) => file.downloadStatus === "idle")
            .map((file) => file.id),
        ),
      );
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
        </div>
        <div className="hidden gap-4 md:flex">
          <TableColumnFilter
            columns={columns}
            onColumnConfigChange={setColumns}
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
          onFileChange={setCurrentViewFile}
          {...useFilesProps}
        />
      )}
      <div className="h-[calc(100vh-13rem)] space-y-4 overflow-hidden">
        {selectedFiles.size > 0 && (
          <div className="flex items-center justify-between rounded-lg bg-muted/50 p-4">
            <span className="text-sm">
              {selectedFiles.size} {selectedFiles.size === 1 ? "file" : "files"}{" "}
              selected
            </span>
            <Button
              size="sm"
              onClick={() => {
                void startDownloadMultiple({
                  files: Array.from(selectedFiles).map((id) => {
                    const file = files.find((f) => f.id === id);
                    return {
                      telegramId: file?.telegramId ?? 0,
                      chatId: file?.chatId ?? 0,
                      messageId: file?.messageId ?? 0,
                      fileId: file?.id ?? 0,
                    };
                  }),
                });
              }}
              disabled={
                selectedFiles.size === 0 || startDownloadMultipleMutating
              }
            >
              {startDownloadMultipleMutating ? (
                <LoaderCircle
                  className="mr-2 h-4 w-4 animate-spin"
                  style={{ strokeWidth: "0.8px" }}
                />
              ) : (
                <>
                  <Download className="mr-2 h-4 w-4" />
                  Download Selected
                </>
              )}
            </Button>
          </div>
        )}

        <div
          className="relative h-full overflow-auto rounded-md border"
          ref={tableParentRef}
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
                  {col.label}
                </div>
              ) : null,
            )}
          </div>
          {size === 1 && isLoading && (
            <div className="sticky left-1/2 top-0 z-10 flex h-full w-full items-center justify-center bg-accent">
              <LoaderPinwheel
                className="h-8 w-8 animate-spin"
                style={{ strokeWidth: "0.8px" }}
              />
            </div>
          )}
          <div className="h-full">
            <div
              className={cn("relative w-full")}
              style={{ height: `${rowVirtual.getTotalSize()}px` }}
            >
              {files.length !== 0 &&
                rowVirtual.getVirtualItems().map((virtualRow) => {
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
