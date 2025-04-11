import { LoaderPinwheel } from "lucide-react";
import React, { useEffect, useState } from "react";
import { useFiles } from "@/hooks/use-files";
import { useWindowVirtualizer } from "@tanstack/react-virtual";
import { FileCard } from "@/components/mobile/file-card";
import { cn } from "@/lib/utils";
import FileDrawer from "@/components/mobile/file-drawer";
import type { TelegramFile } from "@/lib/types";
import { isEqual } from "lodash";
import FileFilters from "@/components/file-filters";
import DraggableElement from "@/components/ui/draggable-element";
import { useLocalStorage } from "@/hooks/use-local-storage";
import FileNotFount from "@/components/file-not-found";
import { MobileFileTagsDrawer } from "@/components/file-tags";

interface FileListProps {
  accountId: string;
  chatId: string;
}

export default function FileList({ accountId, chatId }: FileListProps) {
  const useFilesProps = useFiles(accountId, chatId);
  const [currentViewFile, setCurrentViewFile] = useState<
    TelegramFile | undefined
  >();
  const [currentTagsFile, setCurrentTagsFile] = useState<
    TelegramFile | undefined
  >();
  const [isDrawerOpen, setIsDrawerOpen] = useState(false);
  const [isTagsDrawerOpen, setIsTagsDrawerOpen] = useState(false);
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

  const rowVirtual = useWindowVirtualizer({
    count: hasMore ? files.length + 1 : files.length,
    estimateSize: (index) => {
      const file = files[index];
      if (!file) {
        return 90;
      }
      if (layout === "detailed") {
        return 90;
      }
      return !file.thumbnail ? 116 : 340;
    },
    overscan: 5,
    scrollMargin: 0,
    gap: 10,
  });

  useEffect(() => {
    rowVirtual.measure();
  }, [layout, rowVirtual]);

  useEffect(() => {
    const [lastItem] = [...rowVirtual.getVirtualItems()].reverse();
    if (!lastItem) {
      return;
    }

    if (lastItem.index >= files.length - 1 && hasMore && !isLoading) {
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
    if (!isEqual(file, currentViewFile)) {
      setCurrentViewFile(file);
    }
  }, [currentViewFile, files]);

  return (
    <div className="space-y-4">
      <DraggableElement>
        <FileFilters
          telegramId={accountId}
          chatId={chatId}
          filters={filters}
          onFiltersChange={handleFilterChange}
          clearFilters={clearFilters}
        />
      </DraggableElement>
      {currentViewFile && (
        <FileDrawer
          open={isDrawerOpen}
          onOpenChange={setIsDrawerOpen}
          file={currentViewFile}
          onFileChange={setCurrentViewFile}
          {...useFilesProps}
        />
      )}
      {currentTagsFile && (
        <MobileFileTagsDrawer
          file={currentTagsFile}
          onTagsUpdate={(tags) => {
            void updateField(currentTagsFile.uniqueId, {
              tags: tags.join(","),
            });
          }}
          open={isTagsDrawerOpen}
          onOpenChange={setIsTagsDrawerOpen}
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
            <LoaderPinwheel
              className="h-8 w-8 animate-spin"
              style={{ strokeWidth: "0.8px" }}
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
                    <LoaderPinwheel
                      className="h-8 w-8 animate-spin"
                      style={{ strokeWidth: "0.8px" }}
                    />
                  ) : (
                    <p className="text-muted-foreground">No more files</p>
                  )}
                </div>
              );
            }
            return (
              <FileCard
                key={`${file.id}-${file.uniqueId}-${virtualRow.index}`}
                index={virtualRow.index}
                className={cn("absolute left-0 top-0 flex w-full items-center")}
                style={{
                  height: `${virtualRow.size}px`,
                  transform: `translateY(${virtualRow.start}px)`,
                }}
                ref={rowVirtual.measureElement}
                file={file}
                onFileClick={() => {
                  setCurrentViewFile(file);
                  setIsDrawerOpen(true);
                }}
                onFileTagsClick={() => {
                  setCurrentTagsFile(file);
                  setIsTagsDrawerOpen(true);
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
