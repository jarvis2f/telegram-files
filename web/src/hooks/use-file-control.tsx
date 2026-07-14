import { type TelegramFile } from "@/lib/types";
import useSWRMutation from "swr/mutation";
import { POST } from "@/lib/api";
import { toast } from "@/hooks/use-toast";

export function useFileControl(
  file: TelegramFile,
  updateField?: (uniqueId: string, patch: Partial<TelegramFile>) => Promise<void>,
) {
  const { trigger: startDownload, isMutating: starting } = useSWRMutation(
    file.source === "SEED"
      ? "/files/start-download-multiple"
      : `/${file.telegramId}/file/start-download`,
    (key: string, { arg }: { arg: Record<string, unknown> }) => POST(key, arg),
  );
  const { trigger: cancelDownload, isMutating: cancelling } = useSWRMutation(
    file.source === "SEED"
      ? "/files/cancel-download-multiple"
      : `/${file.telegramId}/file/cancel-download`,
    (key: string, { arg }: { arg: Record<string, unknown> }) => POST(key, arg),
  );
  const { trigger: togglePauseDownload, isMutating: togglingPause } =
    useSWRMutation(
      file.source === "SEED"
        ? "/files/toggle-pause-download-multiple"
        : `/${file.telegramId}/file/toggle-pause-download`,
      (key: string, { arg }: { arg: Record<string, unknown> }) => POST(key, arg),
    );
  const { trigger: removeFile, isMutating: removing } = useSWRMutation(
    `/${file.telegramId}/file/remove`,
    (key, { arg }: { arg: { fileId: number; uniqueId: string } }) =>
      POST(key, arg),
  );
  const { trigger: toggleSeedPauseMutation, isMutating: togglingSeedPause } = useSWRMutation(
    "/files/toggle-pause-download-multiple",
    (key: string, { arg }: { arg: Record<string, unknown> }) => POST(key, arg),
  );
  const { trigger: cancelSeedMutation, isMutating: cancellingSeeding } =
    useSWRMutation(
      "/files/cancel-download-multiple",
      (key: string, { arg }: { arg: Record<string, unknown> }) => POST(key, arg),
    );
  const { trigger: setSeedUploadLimitMutation, isMutating: settingSeedLimit } =
    useSWRMutation(
      "/files/set-upload-limit-multiple",
      (key: string, { arg }: { arg: Record<string, unknown> }) => POST(key, arg),
    );

  const downloadControl = {
    cancel: (fileId: number) => {
      void cancelDownload(
        file.source === "SEED"
          ? { files: [batchDescriptor(file)] }
          : { fileId },
      );
    },
    start: (fileId: number) => {
      if (file) {
        if (file.downloadStatus !== "idle" && file.downloadStatus !== "error") {
          return;
        }
        if (!file.uniqueId || file.uniqueId.trim() === "") {
          toast({
            variant: "error",
            description: "☹️Sorry, this file cannot be downloaded",
          });
          return;
        }
        void startDownload(
          file.source === "SEED"
            ? { files: [batchDescriptor(file)] }
            : { chatId: file.chatId, messageId: file.messageId, fileId },
        );
      }
    },
    togglePause: (fileId: number) => {
      if (file) {
        if (
          file.downloadStatus !== "downloading" &&
          file.downloadStatus !== "paused"
        ) {
          return;
        }
        void togglePauseDownload({
          ...(file.source === "SEED"
            ? { files: [batchDescriptor(file)] }
            : { fileId }),
          isPaused: file.downloadStatus === "downloading",
        });
      }
    },
    remove: (fileId: number) => {
      if (file) {
        void removeFile({ fileId, uniqueId: file.uniqueId });
      }
    },
    toggleSeedPause: async () => {
      if (file) {
        const isPausing =
          !!file.torrentStatus &&
          file.torrentStatus !== "PAUSED" &&
          file.torrentStatus !== "STOPPED";
        if (!isPausing && file.downloadStatus !== "completed") {
          toast({
            variant: "error",
            description: "Please download the file before seeding",
          });
          return;
        }
        const nextStatus = isPausing ? "PAUSED" : "SEEDING";
        const resourceId =
          file.seedResourceId ??
          (file.uniqueId.startsWith("seed:") ? file.uniqueId.slice(5) : null);

        if (!resourceId) {
          toast({
            variant: "error",
            description: "No seeding resource ID found for this file",
          });
          return;
        }

        try {
          await toggleSeedPauseMutation({
            isPaused: isPausing,
            files: [
              {
                telegramId: 0,
                uniqueId: `seed:${resourceId}`,
              },
            ],
          });
          if (updateField) {
            void updateField(file.uniqueId, { torrentStatus: nextStatus });
          }
          toast({
            variant: "success",
            description: isPausing ? "Seeding paused" : "Seeding resumed",
          });
        } catch (error) {
          toast({
            variant: "error",
            description:
              error instanceof Error
                ? error.message
                : "Failed to toggle seeding state",
          });
        }
      }
    },
    cancelSeeding: async () => {
      if (file) {
        const resourceId =
          file.seedResourceId ??
          (file.uniqueId.startsWith("seed:") ? file.uniqueId.slice(5) : null);

        if (!resourceId) {
          toast({
            variant: "error",
            description: "No seeding resource ID found for this file",
          });
          return;
        }

        try {
          await cancelSeedMutation({
            files: [
              {
                telegramId: 0,
                uniqueId: `seed:${resourceId}`,
              },
            ],
          });
          if (updateField) {
            void updateField(file.uniqueId, { torrentStatus: "STOPPED" });
          }
          toast({
            variant: "info",
            description: "Seeding canceled",
          });
        } catch (error) {
          toast({
            variant: "error",
            description:
              error instanceof Error
                ? error.message
                : "Failed to cancel seeding",
          });
        }
      }
    },
    setSeedUploadLimit: async (uploadLimitBytesPerSecond: number) => {
      if (file) {
        if (
          !Number.isSafeInteger(uploadLimitBytesPerSecond) ||
          uploadLimitBytesPerSecond < 0
        ) {
          toast({
            variant: "error",
            description: "Upload limit is invalid",
          });
          return false;
        }

        const resourceId =
          file.seedResourceId ??
          file.sharedResourceId ??
          (file.uniqueId.startsWith("seed:") ? file.uniqueId.slice(5) : null);

        if (!resourceId) {
          toast({
            variant: "error",
            description: "No seeding resource ID found for this file",
          });
          return false;
        }

        try {
          await setSeedUploadLimitMutation({
            uploadLimitBytesPerSecond: String(uploadLimitBytesPerSecond),
            files: [
              {
                telegramId: 0,
                uniqueId: `seed:${resourceId}`,
              },
            ],
          });
          toast({
            variant: "success",
            description:
              uploadLimitBytesPerSecond === 0
                ? "Upload limit cleared"
                : "Upload limit updated",
          });
          return true;
        } catch (error) {
          toast({
            variant: "error",
            description:
              error instanceof Error
                ? error.message
                : "Failed to update upload limit",
          });
          return false;
        }
      }
      return false;
    },
    cancelling,
    starting,
    togglingPause,
    removing,
    togglingSeedPause,
    cancellingSeeding,
    settingSeedLimit,
  };

  return {
    ...downloadControl,
  };
}

function batchDescriptor(file: TelegramFile) {
  return {
    telegramId: file.telegramId,
    chatId: file.chatId,
    messageId: file.messageId,
    fileId: file.id,
    uniqueId: file.uniqueId,
  };
}
