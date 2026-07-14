import { type DownloadStatus, type TelegramFile } from "@/lib/types";
import { useFileControl } from "@/hooks/use-file-control";
import { Button } from "@/components/ui/button";
import {
  CircleStop,
  Download,
  Gauge,
  Link2Off,
  LoaderCircle,
  MessageCircle,
  MoreHorizontal,
  Pause,
  Pencil,
  Play,
  RotateCcw,
  Trash2,
} from "lucide-react";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
  TooltipWrapper,
} from "@/components/ui/tooltip";
import { type ReactNode, useState } from "react";
import { cn } from "@/lib/utils";
import prettyBytes from "pretty-bytes";
import { AnimatePresence, motion } from "framer-motion";
import { useRouter } from "next/navigation";
import { useTelegramMethod } from "@/hooks/use-telegram-method";
import { toast } from "@/hooks/use-toast";
import { useMaybeTelegramChat } from "@/hooks/use-telegram-chat";
import { useSettings } from "@/hooks/use-settings";
import { ShareResourceDialog } from "@/components/share-resource-dialog";
import { EditPublishedResourceDialog } from "@/components/published-resources";
import { useSharePublicationPolicy } from "@/hooks/use-share-publication-policy";
import { useShareEnabled } from "@/hooks/use-share-enabled";
import { canShareFile } from "@/lib/share-publication-policy";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import Image from "next/image";
import { publishedSourceFromTelegramFile } from "@/lib/published-source";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

function TelegramSeedIcon({ className }: { className?: string }) {
  return (
    <Image
      src="/telegram-seed.svg"
      alt=""
      aria-hidden="true"
      width={16}
      height={16}
      className={cn("size-4 rounded-full bg-white", className)}
    />
  );
}

interface ActionButtonProps {
  tooltipText: string;
  icon: ReactNode;
  onClick: () => void;
  loading: boolean;
  isMobile?: boolean;
  disabled?: boolean;
  tone?: "default" | "primary" | "accent" | "danger";
}

const ActionButton = ({
  tooltipText,
  icon,
  onClick,
  loading,
  isMobile,
  disabled,
  tone = "default",
}: ActionButtonProps) => (
  <Tooltip>
    <TooltipTrigger asChild>
      <span className="inline-flex">
        <Button
          variant="ghost"
          size="icon"
          aria-label={tooltipText}
          className={cn(
            "size-7 rounded-md text-muted-foreground shadow-none transition-colors duration-150 focus-visible:ring-2",
            isMobile && "size-8 rounded-lg",
            tone === "primary" &&
              "text-blue-600 hover:bg-blue-500/10 hover:text-blue-700 dark:text-blue-400 dark:hover:text-blue-300",
            tone === "accent" &&
              "hover:bg-cyan-500/10 hover:text-cyan-700 dark:hover:text-cyan-300",
            tone === "danger" &&
              "hover:bg-destructive/10 hover:text-destructive",
          )}
          onClick={onClick}
          disabled={disabled || loading}
        >
          {loading ? <LoaderCircle className="animate-spin" /> : icon}
        </Button>
      </span>
    </TooltipTrigger>
    <TooltipContent>
      <p>{tooltipText}</p>
    </TooltipContent>
  </Tooltip>
);

export default function FileControl({
  file,
  downloadSpeed,
  hovered,
  isMobile,
  updateField,
}: {
  file: TelegramFile;
  downloadSpeed?: number;
  hovered?: boolean;
  isMobile?: boolean;
  updateField?: (
    uniqueId: string,
    patch: Partial<TelegramFile>,
  ) => Promise<void>;
}) {
  const router = useRouter();
  const { executeMethod, isMethodExecuting } = useTelegramMethod();
  const { settings } = useSettings();
  const { chat } = useMaybeTelegramChat() ?? {};
  const [shareOpen, setShareOpen] = useState(false);
  const [editMetadataOpen, setEditMetadataOpen] = useState(false);
  const [uploadLimitOpen, setUploadLimitOpen] = useState(false);
  const publishedSource = publishedSourceFromTelegramFile(file);
  const sharePolicy = useSharePublicationPolicy();
  const showDownloadInfo =
    !hovered &&
    !file.originalDeleted &&
    (file.downloadStatus === "downloading" || file.downloadStatus === "paused");
  const iconSize = isMobile ? "!size-3.5" : "size-4";

  const {
    start,
    starting,
    togglePause,
    togglingPause,
    cancel,
    cancelling,
    remove,
    removing,
    toggleSeedPause,
    togglingSeedPause,
    cancelSeeding,
    cancellingSeeding,
    setSeedUploadLimit,
    settingSeedLimit,
  } = useFileControl(file, updateField);

  const removeBtnProps: ActionButtonProps = {
    onClick: () => remove(file.id),
    tooltipText: "Remove local file",
    icon: <Trash2 className={iconSize} />,
    loading: removing,
    tone: "danger",
  };

  const replyBtnProps: ActionButtonProps = {
    onClick: () => {
      if (file.threadChatId !== 0 && file.messageThreadId !== 0) {
        router.push(
          `/accounts?id=${file.telegramId}&chatId=${file.threadChatId}&messageThreadId=${file.messageThreadId}`,
        );
        return;
      } else {
        void executeMethod({
          data: {
            chatId: file.chatId,
            messageId: file.messageId,
          },
          method: "GetMessageThread",
        })
          .then((result) => {
            if (!result) {
              toast({
                variant: "error",
                description: "Failed to get message thread",
              });
              return;
            }
            const { chatId, messageThreadId } = result as {
              chatId: number;
              messageThreadId: number;
            };
            router.push(
              `/accounts?id=${file.telegramId}&chatId=${chatId}&messageThreadId=${messageThreadId}`,
            );
          })
          .catch(() => {
            toast({
              variant: "error",
              description: "Failed to get message thread",
            });
          });
      }
    },
    tooltipText: "View Comments",
    icon: <MessageCircle className={iconSize} />,
    loading: isMethodExecuting,
  };

  const statusMapping: Record<DownloadStatus, ActionButtonProps[]> = {
    idle: [
      {
        onClick: () => start(file.id),
        tooltipText: "Start Download",
        icon: <Download className={iconSize} />,
        loading: starting,
        tone: "primary",
      },
    ],
    error: [
      {
        onClick: () => start(file.id),
        tooltipText: "Retry",
        icon: <RotateCcw className={iconSize} />,
        loading: starting,
        tone: "primary",
      },
    ],
    downloading: [
      {
        onClick: () => togglePause(file.id),
        tooltipText: "Pause",
        icon: <Pause className={iconSize} />,
        loading: togglingPause,
        tone: "primary",
      },
      {
        onClick: () => cancel(file.id),
        tooltipText: "Cancel download",
        icon: <CircleStop className={iconSize} />,
        loading: cancelling,
        tone: "danger",
      },
    ],
    paused: [
      {
        onClick: () => togglePause(file.id),
        tooltipText: "Resume",
        icon: <Play className={iconSize} />,
        loading: togglingPause,
        tone: "primary",
      },
      {
        onClick: () => cancel(file.id),
        tooltipText: "Cancel download",
        icon: <CircleStop className={iconSize} />,
        loading: cancelling,
        tone: "danger",
      },
    ],
    completed: [removeBtnProps],
  };

  const shareEnabled = useShareEnabled();
  const showReplyAction = chat?.type === "channel" && file.hasReply;
  const showShareAction =
    shareEnabled &&
    file.downloadStatus === "completed" &&
    file.source !== "SEED" &&
    canShareFile(file, sharePolicy) &&
    !file.sharedByMe &&
    file.shareStatus !== "PUBLISHED";
  const hasSeedingMenu =
    shareEnabled &&
    Boolean(file.sharedByMe || file.torrentStatus || file.seedResourceId);
  const canSetSeedUploadLimit =
    file.downloadStatus === "completed" &&
    Boolean(
      file.seedResourceId ??
        file.sharedResourceId ??
        (file.uniqueId.startsWith("seed:") ? file.uniqueId.slice(5) : null),
    );

  const isActivelySeeding =
    file.downloadStatus === "completed" &&
    !!file.torrentStatus &&
    file.torrentStatus !== "STOPPED";
  const statusActions: ActionButtonProps[] = isActivelySeeding
    ? []
    : statusMapping[file.downloadStatus];
  const controlRailClass = cn(
    "flex w-fit items-center gap-0.5",
    isMobile && "gap-1",
  );

  const actionButtons = file.originalDeleted ? (
    <div
      className={cn("flex w-full justify-center", isMobile && "justify-end")}
    >
      <div
        className={controlRailClass}
        onClick={(event) => {
          event.preventDefault();
          event.stopPropagation();
        }}
      >
        <TooltipWrapper content="Missing Original Message">
          <span
            role="img"
            aria-label="Missing original message"
            className="flex size-7 items-center justify-center rounded-lg bg-amber-500/10 text-amber-700 dark:text-amber-400"
          >
            <Link2Off className={iconSize} />
          </span>
        </TooltipWrapper>
        <ActionButton isMobile={isMobile} {...removeBtnProps} />
      </div>
    </div>
  ) : (
    <div
      className={cn(
        "flex w-full justify-center transition-opacity duration-150",
        isMobile && "justify-end",
        !isMobile && !hovered && file.downloadStatus === "completed"
          ? "opacity-40"
          : "opacity-100",
      )}
    >
      <div
        className={controlRailClass}
        onClick={(event) => {
          event.preventDefault();
          event.stopPropagation();
        }}
      >
        {!isMobile && showReplyAction && (
          <ActionButton isMobile={isMobile} {...replyBtnProps} />
        )}
        {!isMobile && showShareAction && (
          <ActionButton
            isMobile={isMobile}
            tooltipText={
              file.shareStatus === "PUBLISH_PENDING"
                ? "Sharing in progress"
                : "Share to telegram-seed"
            }
            icon={<TelegramSeedIcon className={iconSize} />}
            loading={false}
            disabled={file.shareStatus === "PUBLISH_PENDING"}
            tone="accent"
            onClick={() => setShareOpen(true)}
          />
        )}
        {hasSeedingMenu && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Seeding options"
                className={cn(
                  "size-7 rounded-md text-muted-foreground shadow-none transition-colors duration-150 hover:bg-emerald-500/10 hover:text-emerald-700 focus-visible:ring-2 dark:hover:text-emerald-300",
                  isMobile && "size-8 rounded-lg",
                )}
              >
                <MoreHorizontal className={iconSize} />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="min-w-48">
              {file.torrentStatus === "STOPPED" || !file.torrentStatus ? (
                <DropdownMenuItem
                  className="gap-2"
                  disabled={
                    togglingSeedPause || file.downloadStatus !== "completed"
                  }
                  onSelect={(e) => {
                    e.preventDefault();
                    void toggleSeedPause();
                  }}
                >
                  {togglingSeedPause ? (
                    <LoaderCircle className="size-4 animate-spin" />
                  ) : (
                    <Play className="size-4" />
                  )}
                  Start seeding
                </DropdownMenuItem>
              ) : file.torrentStatus === "PAUSED" ? (
                <DropdownMenuItem
                  className="gap-2"
                  disabled={
                    togglingSeedPause || file.downloadStatus !== "completed"
                  }
                  onSelect={(e) => {
                    e.preventDefault();
                    void toggleSeedPause();
                  }}
                >
                  {togglingSeedPause ? (
                    <LoaderCircle className="size-4 animate-spin" />
                  ) : (
                    <Play className="size-4" />
                  )}
                  Resume seeding
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem
                  className="gap-2"
                  disabled={togglingSeedPause}
                  onSelect={(e) => {
                    e.preventDefault();
                    void toggleSeedPause();
                  }}
                >
                  {togglingSeedPause ? (
                    <LoaderCircle className="size-4 animate-spin" />
                  ) : (
                    <Pause className="size-4" />
                  )}
                  Pause seeding
                </DropdownMenuItem>
              )}
              <DropdownMenuItem
                className="gap-2"
                disabled={!publishedSource}
                onSelect={(e) => {
                  e.preventDefault();
                  setEditMetadataOpen(true);
                }}
              >
                <Pencil className="size-4" />
                Edit index-metadata
              </DropdownMenuItem>
              <DropdownMenuItem
                className="gap-2"
                disabled={!canSetSeedUploadLimit}
                onSelect={(e) => {
                  e.preventDefault();
                  setUploadLimitOpen(true);
                }}
              >
                <Gauge className="size-4" />
                Set upload limit
              </DropdownMenuItem>
              {file.torrentStatus !== "STOPPED" && (
                <>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem
                    className="gap-2 text-destructive focus:text-destructive"
                    disabled={cancellingSeeding}
                    onSelect={(e) => {
                      e.preventDefault();
                      void cancelSeeding();
                    }}
                  >
                    {cancellingSeeding ? (
                      <LoaderCircle className="size-4 animate-spin" />
                    ) : (
                      <CircleStop className="size-4" />
                    )}
                    Cancel seeding
                  </DropdownMenuItem>
                </>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
        {isMobile && (showReplyAction || showShareAction) && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="icon"
                aria-label="More file actions"
                className="size-8 rounded-[10px] text-muted-foreground shadow-none"
              >
                <MoreHorizontal className={iconSize} />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="min-w-44">
              {showReplyAction && (
                <DropdownMenuItem
                  className="gap-2"
                  disabled={isMethodExecuting}
                  onClick={replyBtnProps.onClick}
                >
                  {isMethodExecuting ? (
                    <LoaderCircle className="animate-spin" />
                  ) : (
                    <MessageCircle />
                  )}
                  View comments
                </DropdownMenuItem>
              )}
              {showReplyAction && showShareAction && <DropdownMenuSeparator />}
              {showShareAction && (
                <DropdownMenuItem
                  className="gap-2 text-cyan-700 focus:text-cyan-700 dark:text-cyan-300 dark:focus:text-cyan-300"
                  disabled={file.shareStatus === "PUBLISH_PENDING"}
                  onClick={() => setShareOpen(true)}
                >
                  <TelegramSeedIcon />
                  {file.shareStatus === "PUBLISH_PENDING"
                    ? "Sharing in progress"
                    : "Share to telegram-seed"}
                </DropdownMenuItem>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
        {statusActions.map((btnProps) => (
          <ActionButton
            key={btnProps.tooltipText}
            isMobile={isMobile}
            {...btnProps}
          />
        ))}
      </div>
    </div>
  );

  if (isMobile) {
    return (
      <TooltipProvider>
        {actionButtons}
        <ShareResourceDialog
          file={file}
          open={shareOpen}
          onOpenChange={setShareOpen}
        />
        <EditPublishedResourceDialog
          source={editMetadataOpen ? publishedSource : null}
          onOpenChange={(open) => {
            if (!open) setEditMetadataOpen(false);
          }}
          onSaved={async () => {
            setEditMetadataOpen(false);
            router.refresh();
          }}
        />
        <SeedUploadLimitDialog
          open={uploadLimitOpen}
          onOpenChange={setUploadLimitOpen}
          submitting={settingSeedLimit}
          onSubmit={async (uploadLimitBytesPerSecond) => {
            if (await setSeedUploadLimit(uploadLimitBytesPerSecond)) {
              setUploadLimitOpen(false);
            }
          }}
        />
      </TooltipProvider>
    );
  }

  return (
    <TooltipProvider>
      <div className="relative h-8 overflow-hidden">
        <AnimatePresence mode="wait">
          {showDownloadInfo ? (
            <motion.div
              key="downloadInfo"
              initial={{ y: 20, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: -20, opacity: 0 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
              className="absolute flex h-full w-full items-center justify-center"
            >
              <span className="text-nowrap text-xs">
                {file.downloadStatus === "downloading" && downloadSpeed
                  ? `${prettyBytes(downloadSpeed, { bits: settings?.speedUnits === "bits" })}/s`
                  : prettyBytes(file.downloadedSize)}
              </span>
            </motion.div>
          ) : (
            <motion.div
              key="actionButtons"
              initial={{ y: 20, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: -20, opacity: 0 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
              className="absolute flex h-full w-full items-center justify-center"
            >
              {actionButtons}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
      <ShareResourceDialog
        file={file}
        open={shareOpen}
        onOpenChange={setShareOpen}
      />
      <EditPublishedResourceDialog
        source={editMetadataOpen ? publishedSource : null}
        onOpenChange={(open) => {
          if (!open) setEditMetadataOpen(false);
        }}
        onSaved={async () => {
          setEditMetadataOpen(false);
          router.refresh();
        }}
      />
      <SeedUploadLimitDialog
        open={uploadLimitOpen}
        onOpenChange={setUploadLimitOpen}
        submitting={settingSeedLimit}
        onSubmit={async (uploadLimitBytesPerSecond) => {
          if (await setSeedUploadLimit(uploadLimitBytesPerSecond)) {
            setUploadLimitOpen(false);
          }
        }}
      />
    </TooltipProvider>
  );
}

function SeedUploadLimitDialog({
  open,
  onOpenChange,
  submitting,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  submitting: boolean;
  onSubmit: (uploadLimitBytesPerSecond: number) => Promise<void>;
}) {
  const [limitKib, setLimitKib] = useState("0");
  const numericLimit = Number(limitKib);
  const isValid =
    limitKib.trim().length > 0 &&
    Number.isFinite(numericLimit) &&
    numericLimit >= 0;

  async function submit() {
    if (!isValid) return;
    await onSubmit(Math.round(numericLimit * 1024));
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>Set upload limit</DialogTitle>
          <DialogDescription>
            Enter KiB/s for this seed resource. Use 0 for unlimited upload.
          </DialogDescription>
        </DialogHeader>
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <label className="flex flex-col gap-2">
            <span className="text-sm font-medium">Upload limit KiB/s</span>
            <Input
              type="number"
              min={0}
              step={1}
              inputMode="numeric"
              value={limitKib}
              onChange={(event) => setLimitKib(event.target.value)}
            />
          </label>
          <DialogFooter className="gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={!isValid || submitting}>
              {submitting && (
                <LoaderCircle data-icon="inline-start" className="animate-spin" />
              )}
              Save limit
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function MobileFileControl({ file }: { file: TelegramFile }) {
  const {
    start,
    starting,
    togglePause,
    togglingPause,
    cancel,
    cancelling,
    remove,
    removing,
    toggleSeedPause,
    togglingSeedPause,
    cancelSeeding,
    cancellingSeeding,
  } = useFileControl(file);
  const canStart =
    file.downloadStatus === "idle" || file.downloadStatus === "error";
  const isActive =
    file.downloadStatus === "downloading" || file.downloadStatus === "paused";
  const isSeedComplete =
    file.downloadStatus === "completed" &&
    !!file.torrentStatus &&
    file.torrentStatus !== "STOPPED";
  const hasSeeding =
    file.downloadStatus === "completed" &&
    (file.sharedByMe || file.torrentStatus || file.seedResourceId);
  const buttonClassName = "h-10 flex-1 rounded-xl font-medium";

  return (
    <div className="flex w-full items-center gap-2">
      {canStart && (
        <Button
          className={buttonClassName}
          onClick={() => start(file.id)}
          disabled={starting}
        >
          {starting ? (
            <LoaderCircle className="animate-spin" />
          ) : file.downloadStatus === "error" ? (
            <RotateCcw />
          ) : (
            <Download />
          )}
          <span>{file.downloadStatus === "error" ? "Retry" : "Download"}</span>
        </Button>
      )}
      {isActive && (
        <>
          <Button
            className={buttonClassName}
            onClick={() => togglePause(file.id)}
            disabled={togglingPause}
          >
            {togglingPause ? (
              <LoaderCircle className="animate-spin" />
            ) : file.downloadStatus === "downloading" ? (
              <Pause />
            ) : (
              <Play />
            )}
            <span>
              {file.downloadStatus === "downloading" ? "Pause" : "Resume"}
            </span>
          </Button>
          <Button
            variant="outline"
            className={cn(
              buttonClassName,
              "text-destructive hover:border-destructive/30 hover:bg-destructive/10 hover:text-destructive",
            )}
            onClick={() => cancel(file.id)}
            disabled={cancelling}
          >
            {cancelling ? (
              <LoaderCircle className="animate-spin" />
            ) : (
              <CircleStop />
            )}
            <span>Cancel</span>
          </Button>
        </>
      )}
      {hasSeeding && (
        <Button
          className={buttonClassName}
          onClick={() => toggleSeedPause()}
          disabled={togglingSeedPause}
        >
          {togglingSeedPause ? (
            <LoaderCircle className="animate-spin" />
          ) : file.torrentStatus === "SEEDING" ? (
            <Pause />
          ) : (
            <Play />
          )}
          <span>
            {file.torrentStatus === "SEEDING"
              ? "Pause seed"
              : file.torrentStatus === "STOPPED" || !file.torrentStatus
                ? "Start seeding"
                : "Resume seed"}
          </span>
        </Button>
      )}
      {isSeedComplete && (
        <Button
          variant="outline"
          className={cn(
            buttonClassName,
            "text-destructive hover:border-destructive/30 hover:bg-destructive/10 hover:text-destructive",
          )}
          onClick={() => cancelSeeding()}
          disabled={cancellingSeeding}
        >
          {cancellingSeeding ? (
            <LoaderCircle className="animate-spin" />
          ) : (
            <CircleStop />
          )}
          <span>Stop seeding</span>
        </Button>
      )}
      {file.downloadStatus === "completed" && !isSeedComplete && (
        <Button
          variant="outline"
          className={cn(
            buttonClassName,
            "text-destructive hover:border-destructive/30 hover:bg-destructive/10 hover:text-destructive",
          )}
          onClick={() => remove(file.id)}
          disabled={removing}
        >
          {removing ? <LoaderCircle className="animate-spin" /> : <Trash2 />}
          <span>Remove local file</span>
        </Button>
      )}
    </div>
  );
}
