"use client";

import { useContext, useEffect } from "react";
import { SettingsContext } from "@/hooks/use-settings";

const CACHE_KEY = "telegram-files:share-enabled";

function getCachedShareEnabled(): boolean | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (raw !== null) {
      return Boolean(JSON.parse(raw));
    }
  } catch {}
  return null;
}

export function useShareEnabled(): boolean {
  const context = useContext(SettingsContext);

  const hasServerSetting = Boolean(
    context?.settings && "shareEnabled" in context.settings,
  );
  const serverValue = hasServerSetting
    ? Boolean(context?.settings?.shareEnabled)
    : null;

  useEffect(() => {
    if (
      hasServerSetting &&
      serverValue !== null &&
      typeof window !== "undefined"
    ) {
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify(serverValue));
      } catch {}
    }
  }, [hasServerSetting, serverValue]);

  if (hasServerSetting && serverValue !== null) {
    return serverValue;
  }

  const cached = getCachedShareEnabled();
  if (cached !== null) {
    return cached;
  }

  return true;
}
