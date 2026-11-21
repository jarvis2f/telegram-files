import accounts from "./accounts.json";
import files from "./files.json";
import sharing from "./sharing.json";
import common from "./common.json";

export type Locale = "en" | "zh-CN";
export type TranslationValues = Record<string, string | number>;
export type Translate = (message: string, values?: TranslationValues) => string;
export const LOCALE_STORAGE_KEY = "telegram-files-locale";

// 完整的英文原文同时用作默认译文，未识别的服务端消息保留原样。
export const chineseMessages: Record<string, string> = {
  ...accounts,
  ...files,
  ...sharing,
  ...common,
};

export function isLocale(value: unknown): value is Locale {
  return value === "en" || value === "zh-CN";
}

export function translate(
  locale: Locale,
  message: string,
  values?: TranslationValues,
): string {
  const template =
    locale === "zh-CN" && Object.hasOwn(chineseMessages, message)
      ? chineseMessages[message]!
      : message;
  if (!values) return template;
  return template.replace(/\{(\w+)\}/g, (placeholder, key: string) =>
    Object.hasOwn(values, key) ? String(values[key]) : placeholder,
  );
}

export function formatLocalizedDate(
  locale: Locale,
  value: string | number | Date,
  options?: Intl.DateTimeFormatOptions,
): string {
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) return "—";
  return new Intl.DateTimeFormat(locale, options).format(date);
}
