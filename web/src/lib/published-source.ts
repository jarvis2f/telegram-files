import { type ShareAccessScope, type TelegramFile } from "@/lib/types";
import { type PublishedSource } from "@/components/published-resources";

export function publishedSourceFromTelegramFile(
  file: TelegramFile,
): PublishedSource | null {
  if (!file.sharedSourceId) return null;

  return {
    sourceId: file.sharedSourceId,
    resourceId: file.sharedResourceId ?? null,
    fileUniqueId: file.uniqueId,
    fileName: file.fileName,
    fileSize: String(file.size),
    title: file.shareTitle ?? file.fileName ?? file.caption ?? "Telegram file",
    description: file.shareDescription ?? null,
    tags: file.shareTags ?? [],
    category: file.shareCategory ?? null,
    status: file.shareStatus ?? "UNSHARED",
    accessScope: file.shareAccessScope ?? defaultAccessScope(),
    publicMessageUrl: file.sharePublicMessageUrl ?? null,
    downloaded: file.downloadStatus === "completed",
    lastErrorCode: file.shareErrorCode ?? null,
  };
}

function defaultAccessScope(): ShareAccessScope {
  return "OWNER_ONLY";
}
