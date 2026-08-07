import { useEffect, useMemo, useState } from "react";
import {
  type DownloadStatus,
  type FileFilter,
  type TelegramFile,
  type Thumbnail,
  type TransferStatus,
} from "@/lib/types";
import useSWRInfinite from "swr/infinite";
import { useWebsocket } from "@/hooks/use-websocket";
import { WebSocketMessageType } from "@/lib/websocket-types";
import { useLocalStorage } from "@/hooks/use-local-storage";
import { useDebounce, useDebouncedCallback } from "use-debounce";

const DEFAULT_FILTERS: FileFilter = {
  search: "",
  type: "media",
  downloadStatus: undefined,
  transferStatus: undefined,
  offline: false,
  seedOnly: false,
  tags: [],
};

type FileResponse = {
  files: TelegramFile[];
  count: number;
  nextFromMessageId: number;
  nextSeedOffset?: number;
  hasMore?: boolean;
};

export function useFiles(
  accountId: string,
  chatId: string,
  messageThreadId?: number,
  link?: string,
) {
  const noAccountSpecified = accountId === "-1" && chatId === "-1";
  const url = noAccountSpecified
    ? "/files"
    : `/telegram/${accountId}/chat/${chatId}/files`;
  const { lastJsonMessage } = useWebsocket();
  const [latestFilesStatus, setLatestFileStatus] = useState<
    Record<
      string,
      {
        fileId: number;
        downloadStatus: DownloadStatus;
        localPath?: string;
        completionDate?: number;
        downloadedSize: number;
        transferStatus?: TransferStatus;
        thumbnailFile?: Thumbnail;
        torrentStatus?: string;
        sharedByMe?: boolean;
        shareStatus?: TelegramFile["shareStatus"];
        sharedSourceId?: string;
        sharedResourceId?: string;
        shareTitle?: string;
        shareDescription?: string | null;
        shareTags?: string[];
        shareCategory?: string | null;
        shareAccessScope?: TelegramFile["shareAccessScope"];
        sharePublicMessageUrl?: string | null;
        shareErrorCode?: string;
        tags?: string;
        removed?: boolean;
      }
    >
  >({});
  const [filters, setFilters, clearFilters] = useLocalStorage<FileFilter>(
    "telegramFileListFilter",
    { ...DEFAULT_FILTERS, offline: noAccountSpecified },
  );
  const getKey = (page: number, previousPageData: FileResponse) => {
    const params = new URLSearchParams({
      ...(filters.search && {
        search: window.encodeURIComponent(filters.search),
      }),
      ...(filters.type && { type: filters.type }),
      ...(filters.downloadStatus && { downloadStatus: filters.downloadStatus }),
      ...(filters.transferStatus && { transferStatus: filters.transferStatus }),
      ...(filters.offline && { offline: "true" }),
      ...(noAccountSpecified && filters.seedOnly && { seedOnly: "true" }),
      ...(filters.tags.length > 0 && {
        tags: filters.tags.join(","),
      }),
      ...(messageThreadId && { messageThreadId: messageThreadId.toString() }),
      ...(link && { link: window.encodeURIComponent(link) }),
      ...(filters.dateType && { dateType: filters.dateType }),
      ...(filters.dateRange && { dateRange: filters.dateRange.join(",") }),
      ...(filters.sizeRange && { sizeRange: filters.sizeRange.join(",") }),
      ...(filters.sizeUnit && { sizeUnit: filters.sizeUnit }),
      ...(filters.sort && { sort: filters.sort }),
      ...(filters.order && { order: filters.order }),
    });

    if (page === 0) {
      return `${url}?${params.toString()}`;
    }

    if (!previousPageData) {
      return null;
    }

    if (noAccountSpecified && filters.seedOnly) {
      params.set(
        "seedOffset",
        (previousPageData.nextSeedOffset ?? 0).toString(),
      );
    } else {
      params.set(
        "fromMessageId",
        previousPageData.nextFromMessageId.toString(),
      );
    }
    if (filters.offline && previousPageData.files.length > 0) {
      const lastFile =
        previousPageData.files[previousPageData.files.length - 1];
      if (filters.sort === "size") {
        params.set("fromSortField", lastFile!.size.toString());
      } else if (filters.sort === "completion_date") {
        params.set("fromSortField", lastFile!.completionDate.toString());
      } else if (filters.sort === "date") {
        params.set("fromSortField", lastFile!.date.toString());
      } else if (filters.sort === "reaction_count") {
        params.set("fromSortField", lastFile!.reactionCount.toString());
      }
    }
    return `${url}?${params.toString()}`;
  };

  const {
    data: pages,
    isLoading,
    isValidating,
    size,
    setSize,
    error,
    mutate,
  } = useSWRInfinite<FileResponse, Error>(getKey, {
    revalidateFirstPage: false,
    keepPreviousData: true,
  });

  const [debounceLoading] = useDebounce(isLoading || isValidating, 500, {
    leading: true,
    maxWait: 1000,
  });

  // A thumbnail finished downloading in the background; refetch so the list picks up the
  // crisp thumbnailFile. Debounced to coalesce the bursts that happen while browsing.
  const debouncedThumbnailRefetch = useDebouncedCallback(() => {
    void mutate();
  }, 1500);

  useEffect(() => {
    if (lastJsonMessage?.type !== WebSocketMessageType.FILE_STATUS) {
      return;
    }
    const data = lastJsonMessage.data as {
      fileId: number;
      uniqueId: string;
      downloadStatus: DownloadStatus;
      localPath: string;
      completionDate: number;
      downloadedSize: number;
      transferStatus?: TransferStatus;
      thumbnailFile?: Thumbnail;
      torrentStatus?: string;
      removed?: boolean;
      type?: string;
      sharedByMe?: boolean;
      shareStatus?: TelegramFile["shareStatus"];
      sharedSourceId?: string;
      sharedResourceId?: string;
      shareTitle?: string;
      shareDescription?: string | null;
      shareTags?: string[];
      shareCategory?: string | null;
      shareAccessScope?: TelegramFile["shareAccessScope"];
      sharePublicMessageUrl?: string | null;
      shareErrorCode?: string;
    };

    if (data.type === "thumbnail") {
      debouncedThumbnailRefetch();
      return;
    }

    if (data.removed) {
      setLatestFileStatus((prev) => ({
        ...prev,
        [data.uniqueId]: {
          fileId: data.fileId,
          downloadStatus: "idle",
          localPath: undefined,
          completionDate: undefined,
          downloadedSize: 0,
          transferStatus: "idle",
          removed: true,
        },
      }));
      return;
    }

    setLatestFileStatus((prev) => ({
      ...prev,
      [data.uniqueId]: {
        fileId: data.fileId,
        downloadStatus:
          data.downloadStatus ?? prev[data.uniqueId]?.downloadStatus,
        localPath: data.localPath ?? prev[data.uniqueId]?.localPath,
        completionDate:
          data.completionDate ?? prev[data.uniqueId]?.completionDate,
        downloadedSize:
          data.downloadedSize ?? prev[data.uniqueId]?.downloadedSize,
        transferStatus:
          data.transferStatus ?? prev[data.uniqueId]?.transferStatus,
        thumbnailFile: data.thumbnailFile ?? prev[data.uniqueId]?.thumbnailFile,
        torrentStatus: data.torrentStatus ?? prev[data.uniqueId]?.torrentStatus,
        sharedByMe: data.sharedByMe ?? prev[data.uniqueId]?.sharedByMe,
        shareStatus: data.shareStatus ?? prev[data.uniqueId]?.shareStatus,
        sharedSourceId:
          data.sharedSourceId ?? prev[data.uniqueId]?.sharedSourceId,
        sharedResourceId:
          data.sharedResourceId ?? prev[data.uniqueId]?.sharedResourceId,
        shareTitle: data.shareTitle ?? prev[data.uniqueId]?.shareTitle,
        shareDescription:
          data.shareDescription ?? prev[data.uniqueId]?.shareDescription,
        shareTags: data.shareTags ?? prev[data.uniqueId]?.shareTags,
        shareCategory:
          data.shareCategory ?? prev[data.uniqueId]?.shareCategory,
        shareAccessScope:
          data.shareAccessScope ?? prev[data.uniqueId]?.shareAccessScope,
        sharePublicMessageUrl:
          data.sharePublicMessageUrl ??
          prev[data.uniqueId]?.sharePublicMessageUrl,
        shareErrorCode:
          data.shareErrorCode ?? prev[data.uniqueId]?.shareErrorCode,
      },
    }));
  }, [debouncedThumbnailRefetch, lastJsonMessage]);

  useEffect(() => {
    if (noAccountSpecified && !filters.offline) {
      setFilters((prev) => ({
        ...prev,
        offline: true,
      }));
    }
  }, [filters.offline, noAccountSpecified, setFilters]);

  const files = useMemo(() => {
    if (!pages) return [];
    const files: TelegramFile[] = [];
    pages.forEach((page) => {
      page.files.forEach((file) => {
        if (file.originalDeleted && latestFilesStatus[file.uniqueId]?.removed) {
          return;
        }
        const merged = {
          ...file,
          id: latestFilesStatus[file.uniqueId]?.fileId ?? file.id,
          tags: latestFilesStatus[file.uniqueId]?.tags ?? file.tags,
          downloadStatus:
            latestFilesStatus[file.uniqueId]?.downloadStatus ??
            file.downloadStatus,
          localPath:
            latestFilesStatus[file.uniqueId]?.localPath ?? file.localPath,
          completionDate:
            latestFilesStatus[file.uniqueId]?.completionDate ??
            file.completionDate,
          downloadedSize:
            latestFilesStatus[file.uniqueId]?.downloadedSize ??
            file.downloadedSize,
          transferStatus:
            latestFilesStatus[file.uniqueId]?.transferStatus ??
            file.transferStatus,
          thumbnailFile:
            latestFilesStatus[file.uniqueId]?.thumbnailFile ??
            file.thumbnailFile,
          torrentStatus:
            latestFilesStatus[file.uniqueId]?.torrentStatus ??
            file.torrentStatus,
          sharedByMe:
            latestFilesStatus[file.uniqueId]?.sharedByMe ?? file.sharedByMe,
          shareStatus:
            latestFilesStatus[file.uniqueId]?.shareStatus ?? file.shareStatus,
          sharedSourceId:
            latestFilesStatus[file.uniqueId]?.sharedSourceId ??
            file.sharedSourceId,
          sharedResourceId:
            latestFilesStatus[file.uniqueId]?.sharedResourceId ??
            file.sharedResourceId,
          shareTitle:
            latestFilesStatus[file.uniqueId]?.shareTitle ?? file.shareTitle,
          shareDescription:
            latestFilesStatus[file.uniqueId]?.shareDescription ??
            file.shareDescription,
          shareTags:
            latestFilesStatus[file.uniqueId]?.shareTags ?? file.shareTags,
          shareCategory:
            latestFilesStatus[file.uniqueId]?.shareCategory ??
            file.shareCategory,
          shareAccessScope:
            latestFilesStatus[file.uniqueId]?.shareAccessScope ??
            file.shareAccessScope,
          sharePublicMessageUrl:
            latestFilesStatus[file.uniqueId]?.sharePublicMessageUrl ??
            file.sharePublicMessageUrl,
          shareErrorCode:
            latestFilesStatus[file.uniqueId]?.shareErrorCode ??
            file.shareErrorCode,
        };
        // Live WebSocket updates can change a row's status after it was fetched. When a status
        // filter is active, drop rows that no longer match so the filtered view stays consistent
        // (otherwise e.g. a "downloading" filter keeps showing files that just completed).
        if (
          filters.downloadStatus &&
          merged.downloadStatus !== filters.downloadStatus
        ) {
          return;
        }
        if (
          filters.transferStatus &&
          merged.transferStatus !== filters.transferStatus
        ) {
          return;
        }
        files.push(merged);
      });
    });
    files.forEach((file, index) => {
      file.prev = files[index - 1];
      file.next = files[index + 1];
    });
    return files;
  }, [
    pages,
    latestFilesStatus,
    filters.downloadStatus,
    filters.transferStatus,
  ]);

  const hasMore = useMemo(() => {
    if (!pages || pages.length === 0) return true;

    const fetchedCount = pages.reduce((acc, d) => acc + d.files.length, 0);
    const lastPage = pages[pages.length - 1];
    let hasMore = false;
    if (lastPage) {
      if (lastPage.hasMore !== undefined) return lastPage.hasMore;
      const count = lastPage.count;
      hasMore = count > fetchedCount && lastPage.nextFromMessageId !== 0;
    }
    return hasMore;
  }, [pages]);

  const totalCount = useMemo(() => {
    if (!pages || pages.length === 0) return undefined;
    for (let index = pages.length - 1; index >= 0; index -= 1) {
      const count = pages[index]?.count;
      if (count !== undefined) {
        return count;
      }
    }
    return undefined;
  }, [pages]);

  const handleLoadMore = async () => {
    if (isLoading || isValidating || !hasMore || error) return;
    await setSize(size + 1);
  };

  const handleFilterChange = async (newFilters: FileFilter) => {
    if (
      Object.keys(newFilters).every(
        (key) =>
          newFilters[key as keyof FileFilter] ===
          filters[key as keyof FileFilter],
      )
    ) {
      return;
    }
    setFilters(newFilters);
    await setSize(1);
  };

  const updateField = async (
    uniqueId: string,
    patch: Partial<TelegramFile>,
  ) => {
    setLatestFileStatus((prev) => {
      const existing = prev[uniqueId];
      const currentFile = pages
        ?.flatMap((p) => p.files)
        .find((f) => f.uniqueId === uniqueId);
      return {
        ...prev,
        [uniqueId]: {
          fileId: patch.id ?? existing?.fileId ?? currentFile?.id ?? 0,
          downloadStatus:
            patch.downloadStatus ??
            existing?.downloadStatus ??
            currentFile?.downloadStatus ??
            "completed",
          localPath:
            patch.localPath ?? existing?.localPath ?? currentFile?.localPath,
          completionDate:
            patch.completionDate ??
            existing?.completionDate ??
            currentFile?.completionDate,
          downloadedSize:
            patch.downloadedSize ??
            existing?.downloadedSize ??
            currentFile?.downloadedSize ??
            0,
          transferStatus:
            patch.transferStatus ??
            existing?.transferStatus ??
            currentFile?.transferStatus,
          thumbnailFile:
            patch.thumbnailFile ??
            existing?.thumbnailFile ??
            currentFile?.thumbnailFile,
          torrentStatus:
            patch.torrentStatus ??
            existing?.torrentStatus ??
            currentFile?.torrentStatus,
          sharedByMe:
            patch.sharedByMe ?? existing?.sharedByMe ?? currentFile?.sharedByMe,
          shareStatus:
            patch.shareStatus ??
            existing?.shareStatus ??
            currentFile?.shareStatus,
          sharedSourceId:
            patch.sharedSourceId ??
            existing?.sharedSourceId ??
            currentFile?.sharedSourceId,
          sharedResourceId:
            patch.sharedResourceId ??
            existing?.sharedResourceId ??
            currentFile?.sharedResourceId,
          shareTitle:
            patch.shareTitle ?? existing?.shareTitle ?? currentFile?.shareTitle,
          shareDescription:
            patch.shareDescription ??
            existing?.shareDescription ??
            currentFile?.shareDescription,
          shareTags:
            patch.shareTags ?? existing?.shareTags ?? currentFile?.shareTags,
          shareCategory:
            patch.shareCategory ??
            existing?.shareCategory ??
            currentFile?.shareCategory,
          shareAccessScope:
            patch.shareAccessScope ??
            existing?.shareAccessScope ??
            currentFile?.shareAccessScope,
          sharePublicMessageUrl:
            patch.sharePublicMessageUrl ??
            existing?.sharePublicMessageUrl ??
            currentFile?.sharePublicMessageUrl,
          shareErrorCode:
            patch.shareErrorCode ??
            existing?.shareErrorCode ??
            currentFile?.shareErrorCode,
          tags: patch.tags ?? existing?.tags ?? currentFile?.tags,
        },
      };
    });
    await mutate((pages) => {
      if (!pages) return [];

      return pages.map((page) => {
        const newFiles = page.files.map((file) =>
          file.uniqueId === uniqueId ? { ...file, ...patch } : file,
        );
        return {
          ...page,
          files: newFiles,
        };
      });
    }, false);
  };

  return {
    size,
    files,
    filters,
    isLoading: debounceLoading,
    updateField,
    handleFilterChange,
    clearFilters,
    handleLoadMore,
    hasMore,
    loadedCount: files.length,
    totalCount,
  };
}
