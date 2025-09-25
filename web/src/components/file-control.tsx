import { type DownloadStatus, type TelegramFile } from "@/lib/types";
import { useFileControl } from "@/hooks/use-file-control";
import { Button } from "@/components/ui/button";
import {
  ArrowDown,
  FileX,
  Loader2,
  MessageSquareText,
  Pause,
  SquareX,
  StepForward,
  Unlink,
} from "lucide-react";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
  TooltipWrapper,
} from "@/components/ui/tooltip";
import { type ReactNode } from "react";
import prettyBytes from "pretty-bytes";
import { AnimatePresence, motion } from "framer-motion";
import { Badge } from "@/components/ui/badge";
import { useRouter } from "next/navigation";
import { useTelegramMethod } from "@/hooks/use-telegram-method";
import { toast } from "@/hooks/use-toast";
import { useMaybeTelegramChat } from "@/hooks/use-telegram-chat";

interface ActionButtonProps {
  tooltipText: string;
  icon: ReactNode;
  onClick: () => void;
  loading: boolean;
  isMobile?: boolean;
}

const ActionButton = ({
  tooltipText,
  icon,
  onClick,
  loading,
  isMobile,
}: ActionButtonProps) => (
  <Tooltip>
    <TooltipTrigger asChild>
      <Button
        variant={isMobile ? "default" : "ghost"}
        size={isMobile ? "icon" : "xs"}
        onClick={onClick}
      >
        {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : icon}
      </Button>
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
}: {
  file: TelegramFile;
  downloadSpeed?: number;
  hovered?: boolean;
  isMobile?: boolean;
}) {
  const router = useRouter();
  const { executeMethod, isMethodExecuting } = useTelegramMethod();
  const { chat } = useMaybeTelegramChat() ?? {};
  const showDownloadInfo =
    !hovered &&
    !file.originalDeleted &&
    (file.downloadStatus === "downloading" || file.downloadStatus === "paused");
  const iconSize = isMobile ? "!h-3 !w-3" : "h-4 w-4";

  const {
    start,
    starting,
    togglePause,
    togglingPause,
    cancel,
    cancelling,
    remove,
    removing,
  } = useFileControl(file);

  const removeBtnProps: ActionButtonProps = {
    onClick: () => remove(file.id),
    tooltipText: "Remove",
    icon: <FileX className={iconSize} />,
    loading: removing,
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
    icon: <MessageSquareText className={iconSize} />,
    loading: isMethodExecuting,
  };

  const statusMapping: Record<DownloadStatus, ActionButtonProps[]> = {
    idle: [
      {
        onClick: () => start(file.id),
        tooltipText: "Start Download",
        icon: <ArrowDown className={iconSize} />,
        loading: starting,
      },
    ],
    error: [
      {
        onClick: () => start(file.id),
        tooltipText: "Retry",
        icon: <ArrowDown className={iconSize} />,
        loading: starting,
      },
    ],
    downloading: [
      {
        onClick: () => togglePause(file.id),
        tooltipText: "Pause",
        icon: <Pause className={iconSize} />,
        loading: togglingPause,
      },
      {
        onClick: () => cancel(file.id),
        tooltipText: "Cancel",
        icon: <SquareX className={iconSize} />,
        loading: cancelling,
      },
    ],
    paused: [
      {
        onClick: () => togglePause(file.id),
        tooltipText: "Resume",
        icon: <StepForward className={iconSize} />,
        loading: togglingPause,
      },
      {
        onClick: () => cancel(file.id),
        tooltipText: "Cancel",
        icon: <SquareX className={iconSize} />,
        loading: cancelling,
      },
    ],
    completed: [removeBtnProps],
  };

  const actionButtons = file.originalDeleted ? (
    <div className="w-full">
      <div
        className="flex w-full items-center justify-end space-x-4 md:justify-around md:space-x-2"
        onClick={(e) => e.preventDefault()}
      >
        <TooltipWrapper content="Missing Original Message">
          <Badge className="bg-yellow-300 text-yellow-900 hover:bg-yellow-400 dark:bg-yellow-800 dark:text-yellow-300 dark:hover:bg-yellow-700">
            <Unlink className="h-4 w-4" />
          </Badge>
        </TooltipWrapper>
        <ActionButton isMobile={isMobile} {...removeBtnProps} />
      </div>
    </div>
  ) : (
    <div className="w-full">
      <div
        className="flex w-full items-center justify-end space-x-4 md:justify-around md:space-x-2"
        onClick={(e) => e.preventDefault()}
      >
        {chat?.type === "channel" && file.hasReply && (
          <ActionButton isMobile={isMobile} {...replyBtnProps} />
        )}
        {statusMapping[file.downloadStatus].map((btnProps, index) => (
          <ActionButton key={index} isMobile={isMobile} {...btnProps} />
        ))}
      </div>
    </div>
  );

  if (isMobile) {
    return <TooltipProvider>{actionButtons}</TooltipProvider>;
  }

  return (
    <TooltipProvider>
      <div className="relative h-6 overflow-hidden">
        <AnimatePresence mode="wait">
          {showDownloadInfo ? (
            <motion.div
              key="downloadInfo"
              initial={{ y: 20, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: -20, opacity: 0 }}
              transition={{ duration: 0.2, ease: "easeOut" }}
              className="absolute w-full"
            >
              <span className="text-nowrap text-xs">
                {file.downloadStatus === "downloading" && downloadSpeed
                  ? `${prettyBytes(downloadSpeed, { bits: true })}/s`
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
              className="absolute w-full"
            >
              {actionButtons}
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </TooltipProvider>
  );
}

export function MobileFileControl({ file }: { file: TelegramFile }) {
  const { start, starting, togglePause, togglingPause, cancel, cancelling, remove, removing } =
    useFileControl(file);

  return (
    <div className="flex w-full items-center justify-between space-x-2">
      {(file.downloadStatus === "idle" || file.downloadStatus === "error") && (
        <Button className="w-full" onClick={() => start(file.id)}>
          {starting ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <ArrowDown className="h-4 w-4" />
          )}
          <span className="ml-2">Download</span>
        </Button>
      )}
      {(file.downloadStatus === "downloading" ||
        file.downloadStatus === "paused") && (
        <>
          <Button className="w-full" onClick={() => togglePause(file.id)}>
            {togglingPause ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : file.downloadStatus === "downloading" ? (
              <Pause className="h-4 w-4" />
            ) : (
              <StepForward className="h-4 w-4" />
            )}
            <span className="ml-2">
              {file.downloadStatus === "downloading" ? "Pause" : "Resume"}
            </span>
          </Button>
          <Button
            variant="destructive"
            className="w-full"
            onClick={() => cancel(file.id)}
          >
            {cancelling ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <SquareX className="h-4 w-4" />
            )}
            <span className="ml-2">Cancel</span>
          </Button>
        </>
      )}
      {file.downloadStatus === "completed" && (
        <Button variant="outline" className="w-full" onClick={() => remove(file.id)}>
          {removing ? (
            <Loader2 className="h-4 w-4 animate-spin" />
          ) : (
            <FileX className="h-4 w-4" />
          )}
          <span className="ml-2">Remove</span>
        </Button>
      )}
    </div>
  );
}
