"use client";

import TelegramIcon from "@/components/telegram-icon";
import { usePlatformBindingStatus } from "@/hooks/use-platform-binding-status";

export function PlatformTelegramIcon({ className }: { className?: string }) {
  const { isBound } = usePlatformBindingStatus();

  return <TelegramIcon className={className} animated={isBound} />;
}
