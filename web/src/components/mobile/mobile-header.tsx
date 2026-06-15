"use client";

import { Card, CardContent } from "@/components/ui/card";
import {
  ChevronsLeftRightEllipsisIcon,
  Download,
  Ellipsis,
  GalleryHorizontal,
  List,
  UnplugIcon,
} from "lucide-react";
import { useWebsocket } from "@/hooks/use-websocket";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import prettyBytes from "pretty-bytes";
import Link from "next/link";
import { Drawer as DrawerPrimitive } from "vaul";
import TelegramIcon from "@/components/telegram-icon";
import { Button } from "@/components/ui/button";
import {
  Drawer,
  DrawerOverlay,
  DrawerPortal,
  DrawerTitle,
  DrawerTrigger,
} from "@/components/ui/drawer";
import React, { type CSSProperties, useEffect, useState } from "react";
import AccountSelect from "@/components/account-select";
import ChatSelect from "@/components/chat-select";
import { cn } from "@/lib/utils";
import AutomationDialog from "@/components/automation-dialog";
import { Badge } from "@/components/ui/badge";
import ThemeToggleButton from "@/components/theme-toggle-button";
import { SettingsDialog } from "@/components/settings-dialog";
import { Label } from "../ui/label";
import { Toggle } from "@/components/ui/toggle";
import { useLocalStorage } from "@/hooks/use-local-storage";
import { useTelegramChat } from "@/hooks/use-telegram-chat";
import { useSettings } from "@/hooks/use-settings";

export function MobileHeader() {
  const { accountDownloadSpeed } = useWebsocket();
  const { settings } = useSettings();
  const [hidden, setHidden] = useState(false);
  const [lastScrollY, setLastScrollY] = useState(0);

  useEffect(() => {
    const handleScroll = () => {
      const currentScrollY = window.scrollY;
      if (currentScrollY === 0) {
        setHidden(false); // 回到顶部时显示
      } else {
        setHidden(currentScrollY > lastScrollY); // 向下滚动时隐藏
      }
      setLastScrollY(currentScrollY);
    };

    window.addEventListener("scroll", handleScroll);
    return () => window.removeEventListener("scroll", handleScroll);
  }, [lastScrollY]);

  return (
    <Card
      className={cn(
        lastScrollY > 0
          ? "fixed left-0 top-0 z-50 w-full rounded-none border-none bg-white/30 shadow-md backdrop-blur-md transition-transform duration-300 dark:bg-zinc-900/30 dark:shadow-sm dark:shadow-black/30"
          : "mb-4",
        hidden ? "-translate-y-full" : "translate-y-0",
      )}
    >
      <CardContent className="p-4">
        <div className="flex w-full items-center justify-between">
          <Link href={"/"} className="inline-flex">
            <TelegramIcon className="h-6 w-6" />
          </Link>

          {accountDownloadSpeed !== 0 ? (
            <div className="flex items-center gap-2 overflow-hidden text-sm text-muted-foreground">
              <span className="flex-1 text-nowrap">
                {`${prettyBytes(accountDownloadSpeed, { bits: settings?.speedUnits === "bits" })}/s`}
              </span>
              <Download className="h-4 w-4 flex-shrink-0" />
            </div>
          ) : (
            <h3 className="text-lg font-semibold">Telegram File Manager</h3>
          )}

          <MenuDrawer />
        </div>
      </CardContent>
    </Card>
  );
}

function MenuDrawer() {
  const useTelegramAccountProps = useTelegramAccount();
  const { chat } = useTelegramChat();
  const { connectionStatus, reconnect, telegramConnectionState } =
    useWebsocket();
  const [layout, setLayout] = useLocalStorage<"detailed" | "gallery">(
    "telegramFileLayout",
    "detailed",
  );

  return (
    <Drawer
      direction="left"
      shouldScaleBackground={true}
      preventScrollRestoration={true}
    >
      <DrawerTrigger asChild>
        <Button size="xs" variant="ghost">
          <Ellipsis className="h-4 w-4" />
        </Button>
      </DrawerTrigger>
      <DrawerPortal>
        <DrawerOverlay className="bg-black/30 dark:bg-black/50" />
        <DrawerPrimitive.Content
          className={cn(
            "fixed bottom-2 left-2 top-2 z-50 flex w-4/5 outline-none",
          )}
          style={{ "--initial-transform": "calc(100% + 8px)" } as CSSProperties}
          aria-describedby={undefined}
        >
          <div className="flex h-full w-full grow flex-col rounded-[16px] bg-white p-4 shadow-lg dark:bg-zinc-900">
            <DrawerTitle className="mb-6 text-center">
              Telegram Files Manager
            </DrawerTitle>
            <div className="flex h-full flex-col justify-between">
              <div className="flex flex-1 flex-col gap-4">
                <AccountSelect {...useTelegramAccountProps} />
                <ChatSelect disabled={!useTelegramAccountProps.accountId} />
              </div>
              <div className="flex flex-col gap-4">
                <div className="flex flex-col">
                  <Label className="text-xs font-semibold text-muted-foreground">
                    Auto Download
                  </Label>
                  <div className="py-2">
                    {chat ? (
                      <AutomationDialog />
                    ) : (
                      <Button
                        variant="outline"
                        className="w-full"
                        disabled={true}
                      >
                        No chat selected
                      </Button>
                    )}
                  </div>
                </div>
                <div className="flex flex-col">
                  <Label className="text-xs font-semibold text-muted-foreground">
                    Layout
                  </Label>
                  <div className="py-2">
                    <Toggle
                      className="w-full border"
                      pressed={layout === "gallery"}
                      onPressedChange={(pressed) => {
                        setLayout(pressed ? "gallery" : "detailed");
                      }}
                    >
                      {layout === "detailed" ? (
                        <>
                          <List className="h-4 w-4" />
                          <span className="">Detailed Layout</span>
                        </>
                      ) : (
                        <>
                          <GalleryHorizontal className="h-4 w-4" />
                          <span className="">Gallery Layout</span>
                        </>
                      )}
                    </Toggle>
                  </div>
                </div>
              </div>
              <div className="flex items-center justify-between gap-2 py-4">
                <div className="flex flex-wrap items-center gap-2">
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
                    onClick={
                      connectionStatus !== "Open" ? reconnect : undefined
                    }
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

                  {telegramConnectionState &&
                    telegramConnectionState !== "ready" && (
                      <Badge variant="secondary">
                        <UnplugIcon className="mr-1 h-4 w-4" />
                        Telegram: {telegramConnectionState}
                      </Badge>
                    )}
                </div>

                <ThemeToggleButton />
                <SettingsDialog />
              </div>
            </div>
          </div>
        </DrawerPrimitive.Content>
      </DrawerPortal>
    </Drawer>
  );
}
