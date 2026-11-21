import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { LanguageSwitcher } from "@/components/language-switcher";
import { I18nProvider, useI18n } from "@/hooks/use-i18n";
import { chineseMessages, LOCALE_STORAGE_KEY, translate } from "@/i18n";

function Example() {
  const { t, formatDate } = useI18n();
  return (
    <>
      <LanguageSwitcher />
      <p>{t("Files")}</p>
      <p>
        {formatDate("2025-11-19T12:00:00Z", {
          dateStyle: "long",
          timeZone: "UTC",
        })}
      </p>
    </>
  );
}

function renderExample() {
  return render(
    <I18nProvider>
      <Example />
    </I18nProvider>,
  );
}

beforeEach(() => {
  const entries = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (key: string) => entries.get(key) ?? null,
    setItem: (key: string, value: string) => entries.set(key, value),
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.documentElement.lang = "en";
});

describe("interface language", () => {
  it("defaults to English and restores the selected language on a fresh render", () => {
    const { unmount } = renderExample();
    expect(screen.getByText("Files")).toBeVisible();
    expect(screen.getByText("November 19, 2025")).toBeVisible();
    expect(document.documentElement.lang).toBe("en");

    fireEvent.change(screen.getByRole("combobox", { name: "Language" }), {
      target: { value: "zh-CN" },
    });
    expect(screen.getByText("文件")).toBeVisible();
    expect(screen.getByText("2025年11月19日")).toBeVisible();
    expect(document.documentElement.lang).toBe("zh-CN");
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe("zh-CN");

    unmount();
    renderExample();
    expect(screen.getByRole("combobox", { name: "语言" })).toHaveValue("zh-CN");
    expect(screen.getByText("文件")).toBeVisible();

    fireEvent.change(screen.getByRole("combobox", { name: "语言" }), {
      target: { value: "en" },
    });
    expect(screen.getByText("Files")).toBeVisible();
    expect(document.documentElement.lang).toBe("en");
  });

  it("ignores an unsupported stored locale and works without writable storage", () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, "unsupported");
    renderExample();
    expect(screen.getByText("Files")).toBeVisible();
    vi.spyOn(window.localStorage, "setItem").mockImplementation(() => {
      throw new DOMException("Storage is unavailable");
    });
    fireEvent.change(screen.getByRole("combobox", { name: "Language" }), {
      target: { value: "zh-CN" },
    });
    expect(screen.getByText("文件")).toBeVisible();
  });

  it("takes language changes from another tab into account", () => {
    renderExample();
    fireEvent(
      window,
      new StorageEvent("storage", {
        key: LOCALE_STORAGE_KEY,
        newValue: "zh-CN",
      }),
    );
    expect(screen.getByText("文件")).toBeVisible();
  });
});

describe("translation catalog", () => {
  it("interpolates values without replacing unknown messages or user text", () => {
    expect(translate("en", "Delete {tag}", { tag: "{tag}" })).toBe(
      "Delete {tag}",
    );
    expect(translate("zh-CN", "Delete {tag}", { tag: "example" })).toBe(
      "删除标签“example”",
    );
    expect(translate("zh-CN", "Unrecognized API message")).toBe(
      "Unrecognized API message",
    );
    expect(translate("zh-CN", "constructor")).toBe("constructor");
  });

  it("keeps named placeholders in every translated template", () => {
    const placeholders = (text: string) =>
      [...new Set(text.match(/\{\w+\}/g) ?? [])].sort();
    for (const [source, translated] of Object.entries(chineseMessages)) {
      expect(translated.trim(), source).not.toBe("");
      expect(placeholders(translated), source).toEqual(placeholders(source));
    }
  });
});
