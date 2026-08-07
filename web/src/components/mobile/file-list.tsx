import React, { useEffect, useMemo, useState } from "react";
import { useFiles } from "@/hooks/use-files";
import { useWindowVirtualizer } from "@tanstack/react-virtual";
import { FileCard } from "@/components/mobile/file-card";
import { cn } from "@/lib/utils";
import FileDrawer from "@/components/mobile/file-drawer";
import type { TelegramFile } from "@/lib/types";
import { isEqual } from "lodash";
import FileFilters from "@/components/file-filters";
import { useLocalStorage } from "@/hooks/use-local-storage";
import FileNotFount from "@/components/file-not-found";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";

interface FileListProps {
  accountId: string;
  chatId: string;
  link?: string;
}

export default function FileList({ accountId, chatId, link }: FileListProps) {
  const useFilesProps = useFiles(accountId, chatId, undefined, link);
  const [currentViewFile, setCurrentViewFile] = useState<
    TelegramFile | undefined
  >();
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const [openDrawerInViewer, setOpenDrawerInViewer] = useState(false);
  const [layout] = useLocalStorage<"detailed" | "gallery">(
    "telegramFileLayout",
    "detailed",
  );

  const {
    filters,
    updateField,
    handleFilterChange,
    clearFilters,
    isLoading,
    size,
    files,
    hasMore,
    handleLoadMore,
  } = useFilesProps;

  const canPreviewFile = (file: TelegramFile) =>
    file.downloadStatus === "completed" &&
    (file.type === "photo" || file.type === "video");

  const fileHeightSignature = useMemo(
    () =>
      files
        .map((file) =>
          layout === "detailed"
            ? `${file.id}:${file.uniqueId}:detailed`
            : `${file.id}:${file.uniqueId}:${file.thumbnail ? "thumbnail" : "plain"}`,
        )
        .join("|"),
    [files, layout],
  );

  const rowVirtual = useWindowVirtualizer({
    useFlushSync: false,
    count: hasMore ? files.length + 1 : files.length,
    getItemKey: (index) => {
      const file = files[index];
      return file ? `${file.id}-${file.uniqueId}-${index}` : `loader-${index}`;
    },
    estimateSize: (index) => {
      const file = files[index];
      if (!file) {
        return 90;
      }
      if (layout === "detailed") {
        return 108;
      }
      return !file.thumbnail ? 116 : 340;
    },
    overscan: 5,
    scrollMargin: 0,
    gap: 10,
  });

  useEffect(() => {
    rowVirtual.measure();
  }, [fileHeightSignature, rowVirtual]);

  useEffect(() => {
    const [lastItem] = [...rowVirtual.getVirtualItems()].reverse();
    if (!lastItem) {
      return;
    }

    if (lastItem.index >= files.length - 1 && hasMore && !isLoading) {
      void handleLoadMore();
    }
  }, [files.length, handleLoadMore, hasMore, isLoading, rowVirtual]);

  useEffect(() => {
    if (files.length === 0 || !currentViewFile) {
      return;
    }
    const index = files.findIndex(
      (f) => f.id === currentViewFile.id || f.uniqueId === currentViewFile.uniqueId,
    );
    if (index === -1) {
      // 只有在drawer关闭时才清除currentViewFile，避免下载完成时意外关闭
      if (!isDrawerOpen) {
        setCurrentViewFile(undefined);
      }
      return;
    }
    const file = files[index]!;
    if (!isEqual(file, currentViewFile)) {
      // 静默更新文件数据，不触发drawer关闭
      setCurrentViewFile(file);
    }
  }, [files, currentViewFile, isDrawerOpen]);

  return (
    <div className="space-y-4">
      {!link && (
        <FileFilters
          telegramId={accountId}
          chatId={chatId}
          filters={filters}
          onFiltersChange={handleFilterChange}
          clearFilters={clearFilters}
          showMobileLayoutToggle={
            accountId === "-1" && chatId === "-1" && !link
          }
        />
      )}
      {currentViewFile && (
        <FileDrawer
          open={isDrawerOpen}
          onOpenChange={(open) => {
            setIsDrawerOpen(open);
            if (!open) {
              setOpenDrawerInViewer(false);
            }
          }}
          file={currentViewFile}
          onFileChange={(newFile) => {
            setCurrentViewFile(newFile);
            void updateField(newFile.uniqueId, { tags: newFile.tags });
          }}
          initialViewing={openDrawerInViewer}
          {...useFilesProps}
        />
      )}
      <div
        style={{
          height: `${rowVirtual.getTotalSize()}px`,
          width: "100%",
          position: "relative",
        }}
      >
        {size === 1 && isLoading && (
          <div className="fixed left-0 top-0 flex h-full w-full items-center justify-center">
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
        {!isLoading && files.length === 0 && <FileNotFount />}
        {files.length !== 0 &&
          rowVirtual.getVirtualItems().map((virtualRow) => {
            const isLoaderRow = virtualRow.index > files.length - 1;
            const file = files[virtualRow.index]!;
            if (isLoaderRow) {
              return (
                <div
                  className="absolute left-0 top-0 flex w-full items-center justify-center"
                  style={{
                    height: `${virtualRow.size}px`,
                    transform: `translateY(${virtualRow.start}px)`,
                  }}
                  key="loader"
                >
                  {hasMore ? (
                    <DotmTriangle2
                      size={32}
                      dotSize={4}
                      speed={1.4}
                      opacityBase={0.1}
                      opacityMid={0.4}
                      opacityPeak={0.95}
                      ariaLabel="Loading more files"
                    />
                  ) : (
                    <p className="text-muted-foreground">No more files</p>
                  )}
                </div>
              );
            }
            return (
              <FileCard
                key={virtualRow.key}
                index={virtualRow.index}
                className={cn("absolute left-0 top-0 flex w-full items-center")}
                style={{
                  height: `${virtualRow.size}px`,
                  transform: `translateY(${virtualRow.start}px)`,
                }}
                ref={rowVirtual.measureElement}
                file={file}
                onFileClick={() => {
                  setOpenDrawerInViewer(
                    layout === "gallery" && canPreviewFile(file),
                  );
                  setCurrentViewFile(file);
                  setIsDrawerOpen(true);
                }}
                onFileChange={(updatedFile) => {
                  void updateField(updatedFile.uniqueId, { tags: updatedFile.tags });
                }}
                layout={layout}
                {...useFilesProps}
              />
            );
          })}
      </div>
    </div>
  );
}
