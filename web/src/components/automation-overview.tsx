"use client";

import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import type { AutomationChatOverview } from "@/lib/types";
import {
  AlertTriangle,
  ArrowRight,
  Download,
  FolderSync,
  PackageSearch,
  Workflow,
} from "lucide-react";
import { useRouter } from "next/navigation";
import useSWR from "swr";

const HISTORY_PRELOAD_STATE = 1;
const HISTORY_DOWNLOAD_STATE = 2;
const HISTORY_TRANSFER_STATE = 4;

function isStateComplete(state: number, bit: number) {
  return (state & (1 << bit)) !== 0;
}

function enabledCount(item: AutomationChatOverview) {
  return [
    item.auto.preload.enabled,
    item.auto.download.enabled,
    item.auto.transfer.enabled,
  ].filter(Boolean).length;
}

function chatHref(item: AutomationChatOverview) {
  return `/accounts?id=${item.telegramId}&chatId=${item.chatId}`;
}

function fallback(value?: string) {
  return value?.trim().charAt(0).toUpperCase() || "?";
}

function AutomationBadges({ item }: { item: AutomationChatOverview }) {
  return (
    <div className="flex flex-wrap gap-2">
      {item.auto.preload.enabled && (
        <Badge variant="secondary">
          <PackageSearch data-icon="inline-start" />
          Preload
        </Badge>
      )}
      {item.auto.download.enabled && (
        <Badge variant="secondary">
          <Download data-icon="inline-start" />
          Download
        </Badge>
      )}
      {item.auto.transfer.enabled && (
        <Badge variant="secondary">
          <FolderSync data-icon="inline-start" />
          Transfer
        </Badge>
      )}
    </div>
  );
}

function ProgressText({ item }: { item: AutomationChatOverview }) {
  const enabled = [
    item.auto.preload.enabled &&
      isStateComplete(item.auto.state, HISTORY_PRELOAD_STATE),
    item.auto.download.enabled &&
      isStateComplete(item.auto.state, HISTORY_DOWNLOAD_STATE),
    item.auto.transfer.enabled &&
      isStateComplete(item.auto.state, HISTORY_TRANSFER_STATE),
  ].filter(Boolean).length;
  const total = enabledCount(item);

  if (total === 0) {
    return <span className="text-muted-foreground">No active automation</span>;
  }

  return (
    <span className="text-muted-foreground">
      {enabled} / {total} historical jobs complete
    </span>
  );
}

function RuleSummary({ item }: { item: AutomationChatOverview }) {
  if (item.auto.transfer.enabled && item.auto.transfer.rule.destination) {
    return (
      <span className="truncate text-muted-foreground">
        {item.auto.transfer.rule.destination}
      </span>
    );
  }

  if (item.auto.download.enabled) {
    const query = item.auto.download.rule.query;
    const filterExpr = item.auto.download.rule.filterExpr;
    const fileTypes = item.auto.download.rule.fileTypes.join(", ");
    return (
      <span className="truncate text-muted-foreground">
        {query || filterExpr || fileTypes || "Download rule enabled"}
      </span>
    );
  }

  return <span className="text-muted-foreground">Preload enabled</span>;
}

function LoadingState() {
  return (
    <Card className="mx-auto mb-8">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Workflow data-icon="inline-start" />
          Automation Overview
        </CardTitle>
        <CardDescription>
          Loading automated chats across accounts.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </CardContent>
    </Card>
  );
}

export function AutomationOverview() {
  const router = useRouter();
  const {
    data: items,
    error,
    isLoading,
  } = useSWR<AutomationChatOverview[], Error>("/automations/chats");

  if (isLoading) {
    return <LoadingState />;
  }

  if (error) {
    return (
      <Card className="mx-auto mb-8">
        <CardContent className="flex items-center justify-center gap-2 p-6 text-muted-foreground">
          <AlertTriangle data-icon="inline-start" />
          Failed to load automation overview
        </CardContent>
      </Card>
    );
  }

  if (!items || items.length === 0) {
    return (
      <Card className="mx-auto mb-8">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Workflow data-icon="inline-start" />
            Automation Overview
          </CardTitle>
          <CardDescription>No chats have automation enabled.</CardDescription>
        </CardHeader>
      </Card>
    );
  }

  return (
    <Card className="mx-auto mb-8">
      <CardHeader className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
        <div className="flex flex-col gap-1">
          <CardTitle className="flex items-center gap-2">
            <Workflow data-icon="inline-start" />
            Automation Overview
          </CardTitle>
          <CardDescription>
            {items.length} automated chats across all active accounts.
          </CardDescription>
        </div>
        <Badge variant="outline">{items.length} chats</Badge>
      </CardHeader>
      <CardContent>
        <div className="hidden md:block">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Chat</TableHead>
                <TableHead>Account</TableHead>
                <TableHead>Automation</TableHead>
                <TableHead>Progress</TableHead>
                <TableHead>Rule</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((item) => (
                <TableRow
                  key={`${item.telegramId}:${item.chatId}`}
                  className="cursor-pointer"
                  onClick={() => router.push(chatHref(item))}
                >
                  <TableCell>
                    <div className="flex min-w-0 items-center gap-3">
                      <Avatar className="size-8">
                        <AvatarImage
                          src={`data:image/png;base64,${item.chatAvatar}`}
                        />
                        <AvatarFallback>
                          {fallback(item.chatName)}
                        </AvatarFallback>
                      </Avatar>
                      <div className="flex min-w-0 flex-col">
                        <span className="truncate font-medium">
                          {item.chatName}
                        </span>
                        <span className="text-xs text-muted-foreground">
                          {item.chatType}
                        </span>
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <span className="truncate">{item.accountName}</span>
                  </TableCell>
                  <TableCell>
                    <AutomationBadges item={item} />
                  </TableCell>
                  <TableCell>
                    <ProgressText item={item} />
                  </TableCell>
                  <TableCell className="max-w-48">
                    <RuleSummary item={item} />
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label={`Open ${item.chatName}`}
                      onClick={(event) => {
                        event.stopPropagation();
                        router.push(chatHref(item));
                      }}
                    >
                      <ArrowRight data-icon="inline-start" />
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>

        <div className="flex flex-col gap-3 md:hidden">
          {items.map((item) => (
            <button
              key={`${item.telegramId}:${item.chatId}`}
              type="button"
              className={cn(
                "flex w-full flex-col gap-3 rounded-lg border bg-background p-3 text-left",
                "transition-colors hover:bg-muted/50",
              )}
              onClick={() => router.push(chatHref(item))}
            >
              <div className="flex items-center justify-between gap-3">
                <div className="flex min-w-0 items-center gap-3">
                  <Avatar className="size-9">
                    <AvatarImage
                      src={`data:image/png;base64,${item.chatAvatar}`}
                    />
                    <AvatarFallback>{fallback(item.chatName)}</AvatarFallback>
                  </Avatar>
                  <div className="flex min-w-0 flex-col">
                    <span className="truncate font-medium">
                      {item.chatName}
                    </span>
                    <span className="truncate text-xs text-muted-foreground">
                      {item.accountName} · {item.chatType}
                    </span>
                  </div>
                </div>
                <ArrowRight data-icon="inline-start" />
              </div>
              <AutomationBadges item={item} />
              <div className="flex min-w-0 flex-col gap-1 text-xs">
                <ProgressText item={item} />
                <RuleSummary item={item} />
              </div>
            </button>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
