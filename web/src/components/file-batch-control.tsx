import { Button } from "@/components/ui/button";
import {
  CircleStop,
  Download,
  FileX,
  LoaderCircle,
  Pause,
  RadioTower,
  SquareX,
  StepForward,
} from "lucide-react";
import React, { useState } from "react";
import useSWRMutation from "swr/mutation";
import { POST, request } from "@/lib/api";
import { type TelegramFile } from "@/lib/types";
import { TooltipWrapper } from "@/components/ui/tooltip";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "./ui/dialog";
import { toast } from "@/hooks/use-toast";
import { BatchFileTags } from "@/components/file-tags";
import Image from "next/image";
import { useShareEnabled } from "@/hooks/use-share-enabled";
import { useI18n } from "@/hooks/use-i18n";

function getSeedResourceId(file: TelegramFile): string | null {
  return (
    file.seedResourceId ??
    (file.uniqueId.startsWith("seed:") ? file.uniqueId.slice(5) : null)
  );
}

function seedFileMapper(
  file: TelegramFile,
): Record<string, unknown> | null {
  const resourceId = getSeedResourceId(file);
  if (!resourceId) return null;
  return {
    telegramId: 0,
    uniqueId: `seed:${resourceId}`,
  };
}

interface FileBatchControlProps {
  selectedFiles: Set<number>;
  setSelectedFiles: (files: Set<number>) => void;
  files: TelegramFile[];
  updateField?: (
    uniqueId: string,
    patch: Partial<TelegramFile>,
  ) => Promise<void>;
}

export default function FileBatchControl({
  selectedFiles,
  setSelectedFiles,
  files,
  updateField,
}: FileBatchControlProps) {
  const { t } = useI18n();
  const selectedFileObjects = Array.from(selectedFiles)
    .map((id) => files.find((f) => f.id === id))
    .filter(Boolean) as TelegramFile[];

  // Calculate counts for different file states
  const downloadableCounts = selectedFileObjects.filter(
    (file) => file.downloadStatus === "idle",
  ).length;
  const pausableCounts = selectedFileObjects.filter(
    (file) => file.downloadStatus === "downloading",
  ).length;
  const continuableCounts = selectedFileObjects.filter(
    (file) => file.downloadStatus === "paused",
  ).length;
  const cancelDownloadCounts = selectedFileObjects.filter(
    (file) =>
      file.downloadStatus === "downloading" ||
      file.downloadStatus === "paused",
  ).length;
  const shareEnabled = useShareEnabled();
  const seedResumableCounts = shareEnabled
    ? selectedFileObjects.filter(
        (file) =>
          file.downloadStatus === "completed" &&
          (file.torrentStatus === "STOPPED" || file.torrentStatus === "PAUSED"),
      ).length
    : 0;
  const seedStoppableCounts = shareEnabled
    ? selectedFileObjects.filter(
        (file) =>
          file.downloadStatus === "completed" &&
          !!file.torrentStatus &&
          file.torrentStatus !== "STOPPED",
      ).length
    : 0;
  const deletableCounts = selectedFileObjects.filter(
    (file) =>
      file.downloadStatus === "completed" &&
      (!file.torrentStatus || file.torrentStatus === "STOPPED"),
  ).length;
  const sharableFiles = shareEnabled
    ? selectedFileObjects.filter(
        (file) =>
          file.downloadStatus === "completed" &&
          file.source !== "SEED" &&
          !file.sharedByMe &&
          file.shareStatus !== "PUBLISHED" &&
          file.shareStatus !== "PUBLISH_PENDING",
      )
    : [];
  const loadedFiles = selectedFileObjects.filter(
    (file) => file.loaded && file.source !== "SEED",
  );

  const controlButtons = [
    {
      url: "/files/start-download-multiple",
      label: "Download",
      tooltip: t("Download {count} selected files", { count: downloadableCounts }),
      icon: <Download className="mr-2 h-4 w-4" />,
      filter: (file: TelegramFile) => file.downloadStatus === "idle",
      validCount: downloadableCounts,
      showConfirm: downloadableCounts > 5,
    },
    {
      url: "/files/toggle-pause-download-multiple",
      label: "Continue",
      tooltip: t("Continue {count} paused downloads", { count: continuableCounts }),
      className: "bg-green-500 hover:bg-green-600 text-white",
      icon: <StepForward className="mr-2 h-4 w-4" />,
      filter: (file: TelegramFile) => file.downloadStatus === "paused",
      validCount: continuableCounts,
      showConfirm: false,
      extra: { isPaused: false },
    },
    {
      url: "/files/toggle-pause-download-multiple",
      label: "Pause",
      tooltip: t("Pause {count} active downloads", { count: pausableCounts }),
      className: "bg-yellow-500 hover:bg-yellow-600 text-white",
      icon: <Pause className="mr-2 h-4 w-4" />,
      filter: (file: TelegramFile) => file.downloadStatus === "downloading",
      validCount: pausableCounts,
      showConfirm: false,
      extra: { isPaused: true },
    },
    {
      url: "/files/cancel-download-multiple",
      label: "Cancel",
      tooltip: t("Cancel {count} active downloads", { count: cancelDownloadCounts }),
      className: "bg-red-500 hover:bg-red-600 text-white",
      icon: <SquareX className="mr-2 h-4 w-4" />,
      filter: (file: TelegramFile) =>
        file.downloadStatus === "downloading" ||
        file.downloadStatus === "paused",
      validCount: cancelDownloadCounts,
      showConfirm: true,
    },
    {
      url: "/files/toggle-pause-download-multiple",
      label: "Resume Seed",
      tooltip: t("Start/Resume seeding for {count} files", { count: seedResumableCounts }),
      className: "bg-emerald-500 hover:bg-emerald-600 text-white",
      icon: <RadioTower className="mr-2 h-4 w-4" />,
      filter: (file: TelegramFile) =>
        file.downloadStatus === "completed" &&
        (file.torrentStatus === "STOPPED" || file.torrentStatus === "PAUSED"),
      validCount: seedResumableCounts,
      showConfirm: false,
      extra: { isPaused: false },
      fileMapper: seedFileMapper,
    },
    {
      url: "/files/cancel-download-multiple",
      label: "Stop Seed",
      tooltip: t("Stop seeding for {count} files", { count: seedStoppableCounts }),
      className: "bg-red-500 hover:bg-red-600 text-white",
      icon: <CircleStop className="mr-2 h-4 w-4" />,
      filter: (file: TelegramFile) =>
        file.downloadStatus === "completed" &&
        !!file.torrentStatus &&
        file.torrentStatus !== "STOPPED",
      validCount: seedStoppableCounts,
      showConfirm: true,
      fileMapper: seedFileMapper,
    },
    {
      url: "/files/remove-multiple",
      label: "Delete",
      tooltip: t("Delete {count} completed files", { count: deletableCounts }),
      className: "bg-red-500 hover:bg-red-600 text-white",
      icon: <FileX className="mr-2 h-4 w-4" />,
      filter: (file: TelegramFile) =>
        file.downloadStatus === "completed" &&
        (!file.torrentStatus || file.torrentStatus === "STOPPED"),
      validCount: deletableCounts,
      showConfirm: true,
    },
  ];

  const handleTagsUpdate = (tags: string[]) => {
    if (updateField) {
      loadedFiles.forEach((file) => {
        const newTags = tags.join(",");
        void updateField(file.uniqueId, { tags: newTags });
        setSelectedFiles(new Set());
      });
    }
  };

  // Filter buttons to only show those that have at least one valid file
  const visibleButtons = controlButtons.filter(
    (button) => button.validCount > 0,
  );

  return (
    <>
      {selectedFiles.size > 0 && (
        <div className="flex flex-col rounded-lg bg-muted/50 p-4 transition-all duration-300 animate-in slide-in-from-bottom-2 md:flex-row md:items-center md:justify-between">
          <span className="mb-3 text-sm font-medium md:mb-0">
            {t(selectedFiles.size === 1 ? "{count} file selected" : "{count} files selected", { count: selectedFiles.size })}
          </span>
          <div className="flex flex-wrap gap-2">
            {loadedFiles.length > 0 && (
              <BatchFileTags
                files={loadedFiles}
                onTagsUpdate={handleTagsUpdate}
              />
            )}
            {visibleButtons.map((button) => (
              <ControlButton
                key={button.label}
                selectedFiles={selectedFiles}
                setSelectedFiles={setSelectedFiles}
                files={files}
                {...button}
              />
            ))}
            {sharableFiles.length > 0 && (
              <BatchShareButton
                sharableFiles={sharableFiles}
                setSelectedFiles={setSelectedFiles}
              />
            )}
            <Button
              size="sm"
              variant="outline"
              onClick={() => setSelectedFiles(new Set())}
            >
              {t("Clear Selection")}
            </Button>
          </div>
        </div>
      )}
    </>
  );
}

interface ControlButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  url: string;
  label: string;
  icon: React.ReactNode;
  tooltip: string;
  className?: string;
  extra?: Record<string, any>;
  filter: (file: TelegramFile) => boolean;
  validCount: number;
  showConfirm: boolean;
  selectedFiles: Set<number>;
  setSelectedFiles: (files: Set<number>) => void;
  files: TelegramFile[];
  fileMapper?: (file: TelegramFile) => Record<string, unknown> | null;
}

function ControlButton({
  url,
  label,
  icon,
  tooltip,
  className,
  extra,
  filter,
  validCount,
  showConfirm,
  selectedFiles,
  setSelectedFiles,
  files,
  fileMapper,
}: ControlButtonProps) {
  const { t } = useI18n();
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);

  const selectedFileObjects = Array.from(selectedFiles)
    .map((id) => files.find((f) => f.id === id))
    .filter(Boolean) as TelegramFile[];

  // Calculate valid and invalid files based on the filter
  const validFiles = selectedFileObjects.filter(filter);
  const invalidCount = selectedFiles.size - validFiles.length;

  const defaultFileMapper = (file: TelegramFile): Record<string, unknown> => ({
    telegramId: file.telegramId ?? 0,
    chatId: file.chatId ?? 0,
    messageId: file.messageId ?? 0,
    fileId: file.id ?? 0,
    uniqueId: file.uniqueId,
  });

  const { trigger, isMutating } = useSWRMutation(
    url,
    (
      key,
      {
        arg,
      }: {
        arg: {
          files: Array<Record<string, unknown>>;
        } & Record<string, any>;
      },
    ) => POST(key, arg),
    {
      onSuccess: () => {
        setSelectedFiles(new Set());
        toast({
          title: t("{action} action completed", { action: t(label) }),
          description: t("Successfully processed {count} files.", { count: validFiles.length }),
          variant: "success",
        });
      },
    },
  );

  const handleAction = () => {
    const mapper = fileMapper ?? defaultFileMapper;
    const mappedFiles = validFiles
      .map(mapper)
      .filter((f): f is Record<string, unknown> => f !== null);
    if (mappedFiles.length === 0) {
      toast({
        variant: "error",
        description: t("No valid files to process."),
      });
      return;
    }
    void trigger({
      files: mappedFiles,
      ...extra,
    });
    setConfirmDialogOpen(false);
  };

  const handleClick = () => {
    if (showConfirm) {
      setConfirmDialogOpen(true);
    } else {
      handleAction();
    }
  };

  return (
    <>
      <TooltipWrapper content={tooltip}>
        <Button
          size="sm"
          className={className}
          onClick={handleClick}
          disabled={validCount === 0 || isMutating}
        >
          {isMutating ? (
            <LoaderCircle
              className="mr-2 h-4 w-4 animate-spin"
              style={{ strokeWidth: "0.8px" }}
            />
          ) : (
            <>
              {icon}
              {t(label)} {validCount > 0 && `(${validCount})`}
            </>
          )}
        </Button>
      </TooltipWrapper>

      <Dialog open={confirmDialogOpen} onOpenChange={setConfirmDialogOpen}>
        <DialogContent className="max-w-xl sm:max-w-md">
          <DialogHeader className="space-y-2">
            <DialogTitle className="text-center text-xl font-semibold">
              {t("Confirm {action} Action", { action: t(label) })}
            </DialogTitle>
            <div className="flex justify-center">
              {label === "Delete" ? (
                <div className="rounded-full bg-red-100 p-3 dark:bg-red-900/30">
                  <FileX className="h-6 w-6 text-red-600 dark:text-red-400" />
                </div>
              ) : label === "Cancel" ? (
                <div className="rounded-full bg-red-100 p-3 dark:bg-red-900/30">
                  <SquareX className="h-6 w-6 text-red-600 dark:text-red-400" />
                </div>
              ) : label === "Stop Seed" ? (
                <div className="rounded-full bg-red-100 p-3 dark:bg-red-900/30">
                  <CircleStop className="h-6 w-6 text-red-600 dark:text-red-400" />
                </div>
              ) : label === "Download" ? (
                <div className="rounded-full bg-blue-100 p-3 dark:bg-blue-900/30">
                  <Download className="h-6 w-6 text-blue-600 dark:text-blue-400" />
                </div>
              ) : label === "Continue" ? (
                <div className="rounded-full bg-green-100 p-3 dark:bg-green-900/30">
                  <StepForward className="h-6 w-6 text-green-600 dark:text-green-400" />
                </div>
              ) : (
                <div className="rounded-full bg-yellow-100 p-3 dark:bg-yellow-900/30">
                  <Pause className="h-6 w-6 text-yellow-600 dark:text-yellow-400" />
                </div>
              )}
            </div>
          </DialogHeader>

          <div className="pb-6 pt-2">
            <p className="mb-3 text-center text-sm text-muted-foreground">
              {t("Are you sure you want to {action} the selected files?", { action: t(label.toLowerCase()) })}
            </p>

            <div className="mt-4 flex flex-col gap-3">
              {validCount > 0 && (
                <div className="overflow-hidden rounded-lg border border-green-200 dark:border-green-800">
                  <div className="border-b border-green-200 bg-green-50 px-4 py-2 dark:border-green-800 dark:bg-green-900/20">
                    <span className="text-sm font-medium text-green-800 dark:text-green-300">
                      {t("Files to process")}
                    </span>
                  </div>
                  <div className="flex items-center bg-white p-4 dark:bg-background">
                    <div className="mr-3 flex h-8 w-8 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30">
                      <span className="text-sm font-semibold text-green-700 dark:text-green-300">
                        {validCount}
                      </span>
                    </div>
                    <div>
                      <p className="text-sm font-medium">
                        {t(validCount === 1 ? "{count} file will be processed" : "{count} files will be processed", { count: validCount })}
                      </p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        {t("These files are in the correct state for this operation")}
                      </p>
                    </div>
                  </div>
                </div>
              )}

              {invalidCount > 0 && (
                <div className="overflow-hidden rounded-lg border border-red-200 dark:border-red-800">
                  <div className="border-b border-red-200 bg-red-50 px-4 py-2 dark:border-red-800 dark:bg-red-900/20">
                    <span className="text-sm font-medium text-red-800 dark:text-red-300">
                      {t("Files that will be skipped")}
                    </span>
                  </div>
                  <div className="flex items-center bg-white p-4 dark:bg-background">
                    <div className="mr-3 flex h-8 w-8 items-center justify-center rounded-full bg-red-100 dark:bg-red-900/30">
                      <span className="text-sm font-semibold text-red-700 dark:text-red-300">
                        {invalidCount}
                      </span>
                    </div>
                    <div>
                      <p className="text-sm font-medium">
                        {t(invalidCount === 1 ? "{count} file cannot be processed" : "{count} files cannot be processed", { count: invalidCount })}
                      </p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        {t("These files are in an incompatible state for this operation")}
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </div>
          </div>

          <DialogFooter className="gap-3 sm:justify-center">
            <DialogClose asChild>
              <Button variant="outline" className="min-w-24">
                {t("Cancel")}
              </Button>
            </DialogClose>
            <Button
              onClick={handleAction}
              className={`min-w-24 ${
                label === "Delete" || label === "Cancel" || label === "Stop Seed"
                  ? "bg-red-500 text-white hover:bg-red-600"
                  : label === "Continue"
                    ? "bg-green-500 text-white hover:bg-green-600"
                    : label === "Pause"
                      ? "bg-yellow-500 text-white hover:bg-yellow-600"
                      : ""
              }`}
            >
              {t(label)}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function BatchShareButton({
  sharableFiles,
  setSelectedFiles,
}: {
  sharableFiles: TelegramFile[];
  setSelectedFiles: (files: Set<number>) => void;
}) {
  const { t } = useI18n();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const handleShare = async () => {
    setSubmitting(true);
    let successCount = 0;
    let failCount = 0;

    for (const file of sharableFiles) {
      try {
        await request("/share/resources", {
          method: "POST",
          body: JSON.stringify({
            fileUniqueId: file.uniqueId,
            title: file.fileName || file.caption || "Telegram file",
            description: file.caption || null,
            tags: file.tags
              ?.split(",")
              .map((t) => t.trim())
              .filter(Boolean) ?? [],
            category: null,
            accessScope: "OWNER_ONLY",
            publicMessageUrl: null,
            immediateReseed: file.downloadStatus === "completed",
            indexOnly: file.downloadStatus !== "completed",
            autoDownloadOnDemand: file.downloadStatus !== "completed",
          }),
        });
        successCount++;
      } catch {
        failCount++;
      }
    }

    setSubmitting(false);
    setConfirmOpen(false);

    toast({
      variant: failCount > 0 ? (successCount > 0 ? "info" : "error") : "success",
      title: t("Batch share completed"),
      description:
        failCount > 0
          ? t("{count} shared, {failed} failed.", { count: successCount, failed: failCount })
          : t("Successfully shared {count} files.", { count: successCount }),
    });

    setSelectedFiles(new Set());
  };

  return (
    <>
      <TooltipWrapper
        content={t("Share {count} files to telegram-seed", { count: sharableFiles.length })}
      >
        <Button size="sm" onClick={() => setConfirmOpen(true)} disabled={submitting}>
          {submitting ? (
            <LoaderCircle
              className="mr-2 h-4 w-4 animate-spin"
              style={{ strokeWidth: "0.8px" }}
            />
          ) : (
            <>
              <Image
                src="/telegram-seed.svg"
                alt=""
                aria-hidden="true"
                width={16}
                height={16}
                className="mr-2 h-4 w-4 rounded-full bg-white"
              />
              {t("Share ({count})", { count: sharableFiles.length })}
            </>
          )}
        </Button>
      </TooltipWrapper>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent className="max-w-xl sm:max-w-md">
          <DialogHeader className="space-y-2">
            <DialogTitle className="text-center text-xl font-semibold">
              {t("Batch Share to telegram-seed")}
            </DialogTitle>
            <div className="flex justify-center">
              <div className="rounded-full bg-cyan-100 p-3 dark:bg-cyan-900/30">
                <Image
                  src="/telegram-seed.svg"
                  alt=""
                  aria-hidden="true"
                  width={24}
                  height={24}
                  className="h-6 w-6 rounded-full bg-white"
                />
              </div>
            </div>
          </DialogHeader>

          <div className="pb-6 pt-2">
            <p className="mb-3 text-center text-sm text-muted-foreground">
              {t("{count} files will be shared with auto-generated metadata based on current file state.", { count: sharableFiles.length })}
            </p>

            <div className="mt-4 space-y-1 rounded-lg border bg-muted/50 p-4 text-sm text-muted-foreground">
              <p>{t("• Title: file name or caption")}</p>
              <p>{t("• Description: file caption")}</p>
              <p>{t("• Tags: existing file tags")}</p>
              <p>{t("• Access: Owner Only")}</p>
              <p>{t("• Downloaded files will seed immediately")}</p>
            </div>
          </div>

          <DialogFooter className="gap-3 sm:justify-center">
            <DialogClose asChild>
              <Button variant="outline" className="min-w-24">
                {t("Cancel")}
              </Button>
            </DialogClose>
            <Button
              onClick={() => void handleShare()}
              disabled={submitting}
              className="min-w-24"
            >
              {submitting && (
                <LoaderCircle className="mr-2 h-4 w-4 animate-spin" />
              )}
              {t("Share")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
