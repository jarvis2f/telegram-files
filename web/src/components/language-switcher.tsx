"use client";

import { Languages } from "lucide-react";
import { useI18n } from "@/hooks/use-i18n";
import { isLocale } from "@/i18n";
import { cn } from "@/lib/utils";

export function LanguageSwitcher({ className }: { className?: string }) {
  const { locale, setLocale, t } = useI18n();
  return (
    <label
      className={cn(
        "inline-flex shrink-0 items-center gap-1 rounded-md border bg-background px-2 text-sm",
        className,
      )}
    >
      <Languages className="size-4 shrink-0" aria-hidden="true" />
      <span className="sr-only">{t("Language")}</span>
      <select
        value={locale}
        onChange={(event) => {
          if (isLocale(event.target.value)) setLocale(event.target.value);
        }}
        className="h-9 min-w-0 cursor-pointer rounded-sm bg-background text-foreground outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <option value="en" lang="en">
          English
        </option>
        <option value="zh-CN" lang="zh-CN">
          简体中文
        </option>
      </select>
    </label>
  );
}
