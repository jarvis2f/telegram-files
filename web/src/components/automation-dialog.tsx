import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import React, { useEffect, useState } from "react";
import useSWRMutation from "swr/mutation";
import { POST } from "@/lib/api";
import { useDebounce } from "use-debounce";
import { useToast } from "@/hooks/use-toast";
import { AutomationButton } from "@/components/automation-button";
import { useTelegramChat } from "@/hooks/use-telegram-chat";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { Label } from "@/components/ui/label";
import { type Auto } from "@/lib/types";
import { Badge } from "./ui/badge";
import AutomationForm from "@/components/automation-form";
import { Skeleton } from "@/components/ui/skeleton";
import { Download, FolderSync, PackageSearch } from "lucide-react";

const DEFAULT_AUTO: Auto = {
  preload: {
    enabled: false,
  },
  download: {
    enabled: false,
    rule: {
      query: "",
      fileTypes: [],
      downloadHistory: true,
      downloadCommentFiles: false,
      filterExpr: "",
    },
  },
  transfer: {
    enabled: false,
    rule: {
      transferHistory: true,
      destination: "",
      transferPolicy: "GROUP_BY_CHAT",
      duplicationPolicy: "OVERWRITE",
      useCaptionName: false,
      extra: {},
    },
  },
};

function StatusBadge({ enabled }: { enabled: boolean }) {
  return (
    <Badge
      variant={enabled ? "default" : "secondary"}
      className="shrink-0 rounded-full px-2.5 py-0.5 text-xs"
    >
      {enabled ? "Enabled" : "Disabled"}
    </Badge>
  );
}

function AutomationSummarySection({
  title,
  enabled,
  icon,
  children,
}: React.PropsWithChildren<{
  title: string;
  enabled: boolean;
  icon: React.ReactNode;
}>) {
  return (
    <section className="rounded-lg border bg-card p-4 shadow-sm">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
            {icon}
          </div>
          <Label className="truncate text-sm font-semibold">{title}</Label>
        </div>
        <StatusBadge enabled={enabled} />
      </div>
      {children && <div className="mt-4 flex flex-col gap-3">{children}</div>}
    </section>
  );
}

function DetailBlock({
  label,
  value,
  children,
}: React.PropsWithChildren<{ label: string; value?: React.ReactNode }>) {
  return (
    <div className="rounded-md border bg-muted/30 p-3">
      <span className="text-xs font-medium text-muted-foreground">{label}</span>
      {value !== undefined && (
        <div className="mt-1 break-words text-sm text-foreground">{value}</div>
      )}
      {children}
    </div>
  );
}

export default function AutomationDialog() {
  const { accountId } = useTelegramAccount();
  const { isLoading, chat, reload } = useTelegramChat();
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [editMode, setEditMode] = useState(false);
  const [auto, setAuto] = useState<Auto>(DEFAULT_AUTO);
  const { trigger: triggerAuto, isMutating: isAutoMutating } = useSWRMutation(
    !accountId || !chat
      ? undefined
      : `/${accountId}/file/update-auto-settings?telegramId=${accountId}&chatId=${chat?.id}`,
    (
      key,
      {
        arg,
      }: {
        arg: Auto;
      },
    ) => {
      return POST(key, arg);
    },
    {
      onSuccess: () => {
        toast({
          variant: "success",
          title: "Auto settings updated!",
        });
        void reload();
        setEditMode(false);
        setTimeout(() => {
          setOpen(false);
        }, 1000);
      },
    },
  );

  const [debounceIsAutoMutating] = useDebounce(isAutoMutating, 500, {
    leading: true,
  });

  useEffect(() => {
    if (chat?.auto) {
      setAuto(chat.auto);
    } else {
      setAuto(DEFAULT_AUTO);
    }
  }, [chat]);

  if (isLoading) {
    return <Skeleton className="h-8 w-32" />;
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger
        asChild
        onClick={(e) => {
          e.stopPropagation();
          setOpen(!open);
        }}
      >
        {chat && <AutomationButton auto={chat.auto} />}
      </DialogTrigger>
      <DialogContent
        aria-describedby={undefined}
        onPointerDownOutside={() => setOpen(false)}
        onClick={(e) => e.stopPropagation()}
        className="flex h-full w-full flex-col overflow-hidden p-0 md:h-auto md:max-h-[85vh] md:max-w-2xl"
      >
        <DialogHeader className="border-b bg-muted/30 px-5 py-4 text-left">
          <DialogTitle className="truncate text-base">
            Update Auto Settings for {chat?.name ?? "Unknown Chat"}
          </DialogTitle>
          <DialogDescription>
            Configure preload, download, and transfer automation for this chat.
          </DialogDescription>
        </DialogHeader>
        <div className="flex-1 overflow-auto px-5 py-4">
          {!editMode && chat?.auto ? (
            <div className="flex flex-col gap-4">
              <AutomationSummarySection
                title="Auto Preload"
                enabled={chat.auto.preload.enabled}
                icon={<PackageSearch />}
              >
                {(chat.auto.state & (1 << 1)) != 0 && (
                  <p className="text-xs text-muted-foreground">
                    All historical files are preloaded.
                  </p>
                )}
              </AutomationSummarySection>
              <AutomationSummarySection
                title="Auto Download"
                enabled={chat.auto.download.enabled}
                icon={<Download />}
              >
                {auto.download.enabled && (
                  <>
                    {(chat.auto.state & (1 << 2)) != 0 && (
                      <p className="text-xs text-muted-foreground">
                        All historical files are started to be downloaded.
                      </p>
                    )}
                    <div className="flex flex-col gap-3">
                      <DetailBlock
                        label="Query Keyword"
                        value={
                          chat.auto.download.rule.query ||
                          "No keyword specified"
                        }
                      />
                      <DetailBlock
                        label="Filter Expression"
                        value={
                          chat.auto.download.rule.filterExpr ||
                          "No filter expression specified"
                        }
                      />

                      <DetailBlock label="File Types">
                        <div className="mt-2 flex flex-wrap gap-2">
                          {chat.auto.download.rule.fileTypes.length > 0 ? (
                            chat.auto.download.rule.fileTypes.map((type) => (
                              <Badge
                                key={type}
                                variant="secondary"
                                className="capitalize"
                              >
                                {type}
                              </Badge>
                            ))
                          ) : (
                            <span className="text-sm text-muted-foreground">
                              No file types selected
                            </span>
                          )}
                        </div>
                      </DetailBlock>

                      <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
                        <span className="text-xs font-medium text-muted-foreground">
                          Download History
                        </span>
                        <StatusBadge
                          enabled={chat.auto.download.rule.downloadHistory}
                        />
                      </div>

                      <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
                        <span className="text-xs font-medium text-muted-foreground">
                          Download Comment Files
                        </span>
                        <StatusBadge
                          enabled={chat.auto.download.rule.downloadCommentFiles}
                        />
                      </div>
                    </div>
                  </>
                )}
              </AutomationSummarySection>

              <AutomationSummarySection
                title="Auto Transfer"
                enabled={chat.auto.transfer.enabled}
                icon={<FolderSync />}
              >
                {chat.auto.transfer.enabled && (
                  <>
                    {(chat.auto.state & (1 << 4)) != 0 && (
                      <p className="text-xs text-muted-foreground">
                        All historical download files are transferred.
                      </p>
                    )}
                    <div className="flex flex-col gap-3">
                      <DetailBlock
                        label="Destination Folder"
                        value={
                          chat.auto.transfer.rule.destination ||
                          "No destination specified"
                        }
                      />
                      <div className="flex flex-col gap-3 rounded-md border bg-muted/30 p-3">
                        <div className="flex items-center justify-between">
                          <span className="text-xs font-medium text-muted-foreground">
                            Transfer Policy
                          </span>
                          <Badge variant="outline" className="font-normal">
                            {chat.auto.transfer.rule.transferPolicy}
                          </Badge>
                        </div>
                        {chat.auto.transfer.rule.transferPolicy ===
                          "GROUP_BY_AI" && (
                          <div className="w-full whitespace-pre-line rounded-md bg-background p-2 text-xs text-muted-foreground">
                            {chat.auto.transfer.rule.extra.promptTemplate}
                          </div>
                        )}
                      </div>
                      <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
                        <span className="text-xs font-medium text-muted-foreground">
                          Duplication Policy
                        </span>
                        <Badge variant="outline" className="font-normal">
                          {chat.auto.transfer.rule.duplicationPolicy}
                        </Badge>
                      </div>
                      <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
                        <span className="text-xs font-medium text-muted-foreground">
                          Transfer History
                        </span>
                        <StatusBadge
                          enabled={chat.auto.transfer.rule.transferHistory}
                        />
                      </div>
                    </div>
                  </>
                )}
              </AutomationSummarySection>
            </div>
          ) : (
            <AutomationForm auto={auto} onChange={setAuto} />
          )}
        </div>
        <DialogFooter className="border-t bg-background px-5 py-4 gap-2">
          {!editMode && chat?.auto ? (
            <Button variant="outline" onClick={() => setEditMode(true)}>
              Edit
            </Button>
          ) : (
            <>
              <Button
                variant="outline"
                onClick={() => setOpen(false)}
                disabled={debounceIsAutoMutating}
              >
                Cancel
              </Button>
              <Button
                onClick={() => {
                  const folderPathRegex =
                    /^[\/\\]?(?:[^<>:"|?*\/\\]+[\/\\]?)*$/;
                  if (
                    auto?.transfer.enabled &&
                    (auto?.transfer.rule.destination.length === 0 ||
                      !folderPathRegex.test(auto?.transfer.rule.destination))
                  ) {
                    toast({
                      variant: "warning",
                      title: "Invalid destination folder",
                      description:
                        "Please enter a valid destination folder path",
                    });
                    return;
                  }
                  void triggerAuto(auto);
                }}
                disabled={debounceIsAutoMutating}
              >
                {debounceIsAutoMutating ? "Submitting..." : "Submit"}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
