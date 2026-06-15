"use client";

import { Card, CardContent } from "@/components/ui/card";
import {
  ChevronsLeftRightEllipsisIcon,
  Download,
  Ellipsis,
  Home,
  UnplugIcon,
} from "lucide-react";
import { TooltipWrapper } from "./ui/tooltip";
import { Badge } from "@/components/ui/badge";
import { useWebsocket } from "@/hooks/use-websocket";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { SettingsDialog } from "@/components/settings-dialog";
import prettyBytes from "pretty-bytes";
import ChatSelect from "@/components/chat-select";
import Link from "next/link";
import TelegramIcon from "@/components/telegram-icon";
import AutomationDialog from "@/components/automation-dialog";
import useIsMobile from "@/hooks/use-is-mobile";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import ThemeToggleButton from "@/components/theme-toggle-button";
import AccountSelect from "@/components/account-select";
import { useSearchParams } from "next/navigation";
import { useSettings } from "@/hooks/use-settings";

export function Header() {
  const useTelegramAccountProps = useTelegramAccount();
  const { connectionStatus, accountDownloadSpeed, reconnect, telegramConnectionState } =
    useWebsocket();
  const { settings } = useSettings();
  const isMobile = useIsMobile();
  const [showMore, setShowMore] = useState(false);
  const searchParams = useSearchParams();
  const messageThreadId = searchParams.get("messageThreadId");

  return (
    <Card className="mb-6">
      <CardContent className="p-4">
        <div className="relative flex flex-col flex-wrap items-start justify-between gap-4 md:flex-row md:items-center">
          <div className="flex w-full flex-1 flex-col gap-4 md:flex-row md:items-center">
            <Link href={"/"} className="hidden md:inline-flex">
              <TelegramIcon className="h-6 w-6" />
            </Link>

            <div className="w-full md:w-auto">
              <AccountSelect {...useTelegramAccountProps} />
            </div>

            {(!isMobile || showMore) && (
              <>
                <ChatSelect disabled={!useTelegramAccountProps.accountId} />

                {!messageThreadId && <AutomationDialog />}
              </>
            )}
          </div>

          <div className="flex items-center gap-2">
            {accountDownloadSpeed !== 0 && (
              <TooltipWrapper content="Current account download speed">
                <div className="flex items-center gap-2 overflow-hidden text-sm text-muted-foreground">
                  <span className="flex-1 text-nowrap">
                    {`${prettyBytes(accountDownloadSpeed, { bits: settings?.speedUnits === 'bits' })}/s`}
                  </span>
                  <Download className="h-4 w-4 flex-shrink-0" />
                </div>
              </TooltipWrapper>
            )}

            {connectionStatus && (
              <TooltipWrapper
                content={
                  connectionStatus === "Open"
                    ? "Live updates connected"
                    : "Live updates disconnected — click to reconnect"
                }
              >
                <Badge
                  variant={
                    connectionStatus === "Open" ? "default" : "secondary"
                  }
                  className={
                    connectionStatus !== "Open" ? "cursor-pointer" : undefined
                  }
                  role={connectionStatus !== "Open" ? "button" : undefined}
                  tabIndex={connectionStatus !== "Open" ? 0 : undefined}
                  aria-label={
                    connectionStatus !== "Open"
                      ? "Reconnect live updates"
                      : undefined
                  }
                  onClick={connectionStatus !== "Open" ? reconnect : undefined}
                  onKeyDown={
                    connectionStatus !== "Open"
                      ? (e) => {
                          if (e.key === "Enter" || e.key === " ") {
                            e.preventDefault();
                            reconnect();
                          }
                        }
                      : undefined
                  }
                >
                  {connectionStatus === "Open" ? (
                    <ChevronsLeftRightEllipsisIcon className="mr-1 h-4 w-4" />
                  ) : (
                    <UnplugIcon className="mr-1 h-4 w-4" />
                  )}
                  {connectionStatus}
                </Badge>
              </TooltipWrapper>
            )}

            {telegramConnectionState && telegramConnectionState !== "ready" && (
              <TooltipWrapper content="Telegram connection state">
                <Badge variant="secondary">
                  <UnplugIcon className="mr-1 h-4 w-4" />
                  Telegram: {telegramConnectionState}
                </Badge>
              </TooltipWrapper>
            )}

            <ThemeToggleButton />

            <SettingsDialog />
          </div>

          <Button
            size="xs"
            variant="ghost"
            onClick={() => (location.href = "/")}
            className="absolute bottom-[0.3rem] right-10 md:hidden"
          >
            <Home className="h-4 w-4" />
          </Button>

          <Button
            size="xs"
            variant="ghost"
            onClick={() => setShowMore(!showMore)}
            className="absolute bottom-[0.3rem] right-0 md:hidden"
          >
            <Ellipsis className="h-4 w-4" />
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
