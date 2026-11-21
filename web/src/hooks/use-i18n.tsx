"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { enUS, zhCN } from "date-fns/locale";
import {
  formatLocalizedDate,
  isLocale,
  LOCALE_STORAGE_KEY,
  translate,
  type Locale,
  type Translate,
} from "@/i18n";

function createFormatters(locale: Locale) {
  const t: Translate = (message, values) => translate(locale, message, values);
  return {
    t,
    formatDate: (
      value: string | number | Date,
      options?: Intl.DateTimeFormatOptions,
    ) => formatLocalizedDate(locale, value, options),
    formatNumber: (value: number, options?: Intl.NumberFormatOptions) =>
      new Intl.NumberFormat(locale, options).format(value),
    dateLocale: locale === "zh-CN" ? zhCN : enUS,
  };
}

type I18nContextValue = ReturnType<typeof createFormatters> & {
  locale: Locale;
  setLocale: (locale: Locale) => void;
};

const I18nContext = createContext<I18nContextValue>({
  locale: "en",
  setLocale: () => undefined,
  ...createFormatters("en"),
});

export function I18nProvider({ children }: { children: ReactNode }) {
  // 服务端和首次客户端渲染均使用英文，避免静态导出的 hydration 不一致。
  const [locale, updateLocale] = useState<Locale>("en");

  useEffect(() => {
    try {
      const stored = localStorage.getItem(LOCALE_STORAGE_KEY);
      if (isLocale(stored)) updateLocale(stored);
    } catch {
      // 浏览器禁止存储时，语言切换仍可在当前页面正常使用。
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === LOCALE_STORAGE_KEY || event.key === null) {
        updateLocale(isLocale(event.newValue) ? event.newValue : "en");
      }
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((nextLocale: Locale) => {
    if (!isLocale(nextLocale)) return;
    updateLocale(nextLocale);
    try {
      localStorage.setItem(LOCALE_STORAGE_KEY, nextLocale);
    } catch {
      // 存储失败不应阻止用户使用所选语言。
    }
  }, []);

  const value = useMemo(
    () => ({ locale, setLocale, ...createFormatters(locale) }),
    [locale, setLocale],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  return useContext(I18nContext);
}
