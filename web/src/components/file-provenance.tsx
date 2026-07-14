import type { TelegramFile } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { Database, Radio, Share2 } from "lucide-react";

const SHARE_STATUS_LABELS: Record<string, string> = {
  PUBLISH_PENDING: "Pending",
  PUBLISHED: "Published",
  FAILED: "Failed",
};

export function FileSource({
  file,
  className,
  showEmpty = true,
}: {
  file: TelegramFile;
  className?: string;
  showEmpty?: boolean;
}) {
  if (!file.source) {
    return showEmpty ? (
      <span className="text-xs text-muted-foreground">—</span>
    ) : null;
  }

  const isSeed = file.source === "SEED";
  const SourceIcon = isSeed ? Database : Radio;

  return (
    <div
      className={cn(
        "flex items-center justify-center gap-1 whitespace-nowrap text-xs",
        className,
      )}
    >
      <SourceIcon
        aria-hidden="true"
        className={cn(
          "h-3.5 w-3.5",
          isSeed ? "text-slate-600" : "text-blue-600",
        )}
      />
      <span className="font-medium text-foreground">
        {isSeed ? "Seed" : "Telegram"}
      </span>
      {file.acquiredVia === "SEED" && !isSeed && (
        <span className="text-violet-600">· via PT</span>
      )}
    </div>
  );
}

export function FileSharing({
  file,
  className,
  showEmpty = true,
}: {
  file: TelegramFile;
  className?: string;
  showEmpty?: boolean;
}) {
  if (!file.shareStatus || file.shareStatus === "UNSHARED") {
    return showEmpty ? (
      <span className="text-xs text-muted-foreground">—</span>
    ) : null;
  }

  const label = file.sharedByMe
    ? "Shared"
    : (SHARE_STATUS_LABELS[file.shareStatus] ?? file.shareStatus);

  return (
    <Badge
      variant="outline"
      className={cn(
        "h-5 gap-1 whitespace-nowrap border-cyan-200 bg-cyan-50 px-1.5 text-[11px] font-medium text-cyan-700 hover:bg-cyan-100 dark:border-cyan-800 dark:bg-cyan-950/50 dark:text-cyan-300 dark:hover:bg-cyan-950",
        file.shareStatus === "FAILED" &&
          "border-red-200 bg-red-50 text-red-700 hover:bg-red-100 dark:border-red-800 dark:bg-red-950/50 dark:text-red-300 dark:hover:bg-red-950",
        className,
      )}
    >
      <Share2 aria-hidden="true" className="h-3 w-3" />
      {label}
    </Badge>
  );
}
