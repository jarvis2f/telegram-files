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
import { useI18n } from "@/hooks/use-i18n";
import { AutomationButton } from "@/components/automation-button";
import { useTelegramChat } from "@/hooks/use-telegram-chat";
import { Label } from "@/components/ui/label";
import { type Auto } from "@/lib/types";
import { Badge } from "./ui/badge";
import AutomationForm from "@/components/automation-form";
import { Skeleton } from "@/components/ui/skeleton";
import { Download, FolderSync, PackageSearch } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { isValidFolderPath } from "@/lib/folder-path";

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
  const { t } = useI18n();

  return (
    <Badge
      variant={enabled ? "default" : "secondary"}
      className="shrink-0 rounded-full px-2.5 py-0.5 text-xs"
    >
      {enabled ? t("Enabled") : t("Disabled")}
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
  const { t } = useI18n();
  const accountId = useSearchParams().get("id") ?? undefined;
  const { isLoading, chat, reload } = useTelegramChat();
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [editMode, setEditMode] = useState(false);
  const [auto, setAuto] = useState<Auto>(DEFAULT_AUTO);
  const mutationKey =
    accountId && chat
      ? `/${accountId}/file/update-auto-settings?telegramId=${accountId}&chatId=${chat.id}`
      : undefined;
  const { trigger: triggerAuto, isMutating: isAutoMutating } = useSWRMutation(
    mutationKey,
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
          title: t("Auto settings updated!"),
        });
        void reload();
        setEditMode(false);
        setTimeout(() => {
          setOpen(false);
        }, 1000);
      },
      onError: (error: Error) => {
        toast({
          variant: "error",
          title: t("Failed to update auto settings"),
          description: t(error.message),
        });
      },
    },
  );

  const [debounceIsAutoMutating] = useDebounce(isAutoMutating, 500, {
    leading: true,
  });

  useEffect(() => {
    // Don't clobber in-progress edits: SWR revalidation (e.g. on window
    // refocus) replaces `chat` while the form is open, which used to reset
    // the local draft and cause silently-lost rule edits on save.
    if (editMode) {
      return;
    }
    if (chat?.auto) {
      setAuto(chat.auto);
    } else {
      setAuto(DEFAULT_AUTO);
    }
  }, [chat, editMode]);

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
            {t("Update Auto Settings for {name}", {
              name: chat?.name ?? t("Unknown Chat"),
            })}
          </DialogTitle>
          <DialogDescription>
            {t(
              "Configure preload, download, and transfer automation for this chat.",
            )}
          </DialogDescription>
        </DialogHeader>
        <div className="flex-1 overflow-auto px-5 py-4">
          {!editMode && chat?.auto ? (
            <div className="flex flex-col gap-4">
              <AutomationSummarySection
                title={t("Auto Preload")}
                enabled={chat.auto.preload.enabled}
                icon={<PackageSearch />}
              >
                {(chat.auto.state & (1 << 1)) != 0 && (
                  <p className="text-xs text-muted-foreground">
                    {t("All historical files are preloaded.")}
                  </p>
                )}
              </AutomationSummarySection>
              <AutomationSummarySection
                title={t("Auto Download")}
                enabled={chat.auto.download.enabled}
                icon={<Download />}
              >
                {auto.download.enabled && (
                  <>
                    {(chat.auto.state & (1 << 2)) != 0 && (
                      <p className="text-xs text-muted-foreground">
                        {t(
                          "All historical files are started to be downloaded.",
                        )}
                      </p>
                    )}
                    <div className="flex flex-col gap-3">
                      <DetailBlock
                        label={t("Query Keyword")}
                        value={
                          chat.auto.download.rule.query ||
                          t("No keyword specified")
                        }
                      />
                      <DetailBlock
                        label={t("Filter Expression")}
                        value={
                          chat.auto.download.rule.filterExpr ||
                          t("No filter expression specified")
                        }
                      />

                      <DetailBlock label={t("File Types")}>
                        <div className="mt-2 flex flex-wrap gap-2">
                          {chat.auto.download.rule.fileTypes.length > 0 ? (
                            chat.auto.download.rule.fileTypes.map((type) => (
                              <Badge
                                key={type}
                                variant="secondary"
                                className="capitalize"
                              >
                                {t(type)}
                              </Badge>
                            ))
                          ) : (
                            <span className="text-sm text-muted-foreground">
                              {t("No file types selected")}
                            </span>
                          )}
                        </div>
                      </DetailBlock>

                      <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
                        <span className="text-xs font-medium text-muted-foreground">
                          {t("Download History")}
                        </span>
                        <StatusBadge
                          enabled={chat.auto.download.rule.downloadHistory}
                        />
                      </div>

                      <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
                        <span className="text-xs font-medium text-muted-foreground">
                          {t("Download Comment Files")}
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
                title={t("Auto Transfer")}
                enabled={chat.auto.transfer.enabled}
                icon={<FolderSync />}
              >
                {chat.auto.transfer.enabled && (
                  <>
                    {(chat.auto.state & (1 << 4)) != 0 && (
                      <p className="text-xs text-muted-foreground">
                        {t("All historical download files are transferred.")}
                      </p>
                    )}
                    <div className="flex flex-col gap-3">
                      <DetailBlock
                        label={t("Destination Folder")}
                        value={
                          chat.auto.transfer.rule.destination ||
                          t("No destination specified")
                        }
                      />
                      <div className="flex flex-col gap-3 rounded-md border bg-muted/30 p-3">
                        <div className="flex items-center justify-between">
                          <span className="text-xs font-medium text-muted-foreground">
                            {t("Transfer Policy")}
                          </span>
                          <Badge variant="outline" className="font-normal">
                            {t(chat.auto.transfer.rule.transferPolicy)}
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
                          {t("Duplication Policy")}
                        </span>
                        <Badge variant="outline" className="font-normal">
                          {t(chat.auto.transfer.rule.duplicationPolicy)}
                        </Badge>
                      </div>
                      <div className="flex items-center justify-between rounded-md border bg-muted/30 p-3">
                        <span className="text-xs font-medium text-muted-foreground">
                          {t("Transfer History")}
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
        <DialogFooter className="gap-2 border-t bg-background px-5 py-4">
          {!editMode && chat?.auto ? (
            <Button variant="outline" onClick={() => setEditMode(true)}>
              {t("Edit")}
            </Button>
          ) : (
            <>
              <Button
                variant="outline"
                onClick={() => setOpen(false)}
                disabled={debounceIsAutoMutating}
              >
                {t("Cancel")}
              </Button>
              <Button
                onClick={() => {
                  if (
                    auto?.transfer.enabled &&
                    !isValidFolderPath(auto?.transfer.rule.destination)
                  ) {
                    toast({
                      variant: "warning",
                      title: t("Invalid destination folder"),
                      description: t(
                        "Please enter a valid destination folder path",
                      ),
                    });
                    return;
                  }
                  if (!mutationKey) {
                    toast({
                      variant: "warning",
                      title: t("Account or chat is still loading"),
                      description: t("Please wait a moment and try again."),
                    });
                    return;
                  }
                  void triggerAuto(auto);
                }}
                disabled={debounceIsAutoMutating || !mutationKey}
              >
                {debounceIsAutoMutating ? t("Submitting...") : t("Submit")}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
