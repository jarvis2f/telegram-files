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
import { PlatformTelegramIcon } from "@/components/platform-telegram-icon";
import { Button } from "@/components/ui/button";
import {
  Drawer,
  DrawerContent,
  DrawerTitle,
  DrawerTrigger,
} from "@/components/ui/drawer";
import React, {
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
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

const HEADER_HIDE_DISTANCE = 108;
const HEADER_SHOW_DISTANCE = 16;

export function MobileHeader() {
  const { accountDownloadSpeed } = useWebsocket();
  const { settings } = useSettings();
  const [hidden, setHidden] = useState(false);
  const [isScrolled, setIsScrolled] = useState(false);
  const lastScrollYRef = useRef(0);
  const directionStartYRef = useRef(0);
  const scrollDirectionRef = useRef<"up" | "down" | null>(null);

  const isOverlayOpen = useCallback(
    () =>
      document.querySelector(
        [
          "[role='dialog'][data-state='open']",
          "[data-popup-open]",
          "[data-radix-popper-content-wrapper]",
        ].join(","),
      ) !== null,
    [],
  );

  useEffect(() => {
    const initialScrollY = Math.max(0, window.scrollY);
    lastScrollYRef.current = initialScrollY;
    directionStartYRef.current = initialScrollY;
    setIsScrolled(initialScrollY > 8);

    const handleScroll = () => {
      const currentScrollY = Math.max(0, window.scrollY);

      if (isOverlayOpen()) {
        setHidden(false);
        lastScrollYRef.current = currentScrollY;
        directionStartYRef.current = currentScrollY;
        scrollDirectionRef.current = null;
        return;
      }

      if (currentScrollY <= 8) {
        setHidden(false);
        setIsScrolled(false);
        lastScrollYRef.current = 0;
        directionStartYRef.current = 0;
        scrollDirectionRef.current = null;
        return;
      }

      setIsScrolled(true);
      const previousScrollY = lastScrollYRef.current;

      if (currentScrollY > previousScrollY) {
        if (scrollDirectionRef.current !== "down") {
          scrollDirectionRef.current = "down";
          directionStartYRef.current = previousScrollY;
        }

        if (
          currentScrollY - directionStartYRef.current >=
          HEADER_HIDE_DISTANCE
        ) {
          setHidden(true);
        }
      } else if (currentScrollY < previousScrollY) {
        if (scrollDirectionRef.current !== "up") {
          scrollDirectionRef.current = "up";
          directionStartYRef.current = previousScrollY;
        }

        if (
          directionStartYRef.current - currentScrollY >=
          HEADER_SHOW_DISTANCE
        ) {
          setHidden(false);
        }
      }

      lastScrollYRef.current = currentScrollY;
    };

    window.addEventListener("scroll", handleScroll, { passive: true });
    return () => window.removeEventListener("scroll", handleScroll);
  }, [isOverlayOpen]);

  return (
    <Card
      className={cn(
        "sticky top-0 z-40 mb-4 transition-transform duration-300",
        isScrolled
          ? "-mx-4 w-[calc(100%+2rem)] rounded-none border-none bg-white/30 shadow-md backdrop-blur-md dark:bg-zinc-900/30 dark:shadow-sm dark:shadow-black/30"
          : "w-full",
        hidden ? "-translate-y-full" : "translate-y-0",
      )}
    >
      <CardContent className="p-4">
        <div className="flex w-full items-center justify-between">
          <Link href={"/"} className="inline-flex">
            <PlatformTelegramIcon className="size-6" />
          </Link>

          {accountDownloadSpeed !== 0 ? (
            <div className="flex items-center gap-2 overflow-hidden text-sm text-muted-foreground">
              <span className="flex-1 text-nowrap">
                {`${prettyBytes(accountDownloadSpeed, { bits: settings?.speedUnits === "bits" })}/s`}
              </span>
              <Download className="h-4 w-4 flex-shrink-0" />
            </div>
          ) : (
            <h3 className="text-lg font-semibold">Telegram Files Manager</h3>
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
    <Drawer swipeDirection="left">
      <DrawerTrigger
        render={
          <Button size="xs" variant="ghost">
            <Ellipsis className="h-4 w-4" />
          </Button>
        }
      />
      <DrawerContent className="w-4/5">
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
                    className="w-full h-10 border data-[state=on]:bg-transparent data-[state=on]:hover:bg-muted"
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

              <div className="flex justify-between">
                <div className="flex items-center gap-2">
                  <Badge
                    variant={
                      connectionStatus === "Open" ? "default" : "secondary"
                    }
                    onClick={
                      connectionStatus !== "connected" ? reconnect : undefined
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
                        {telegramConnectionState}
                      </Badge>
                    )}
                </div>

                <ThemeToggleButton />
                <SettingsDialog />
              </div>
            </div>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  );
}
