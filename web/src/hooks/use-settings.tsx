"use client";
import React, { createContext, type ReactNode, useContext } from "react";
import useSWR from "swr";
import { type SettingKey, SettingKeys, type Settings } from "@/lib/types";
import { request } from "@/lib/api";
import { toast } from "@/hooks/use-toast";

interface SettingsContextType {
  isLoading: boolean;
  settings?: Settings;
  setSetting: (key: SettingKey, value: string) => Promise<void>;
  updateSettings: (updates?: Partial<Settings>) => Promise<void>;
}

const SettingsContext = createContext<SettingsContextType | undefined>(
  undefined,
);

interface SettingsProviderProps {
  children: ReactNode;
  keys?: SettingKey[];
}

export const SettingsProvider: React.FC<SettingsProviderProps> = ({
  children,
  keys = [...SettingKeys],
}) => {
  const {
    data: settings,
    isLoading,
    mutate,
  } = useSWR(
    keys,
    (keys) =>
      request(`/settings?keys=${keys.join(",")}`).then(
        (data) => data as Settings,
      ),
    {
      revalidateOnFocus: false,
    },
  );

  // 更新配置项方法
  const updateSettings = async (updates?: Partial<Settings>) => {
    await request("/settings/create", {
      body: updates ? JSON.stringify(updates) : JSON.stringify(settings),
      method: "POST",
    });
    // 更新缓存
    if (updates) {
      // eslint-disable-next-line @typescript-eslint/ban-ts-comment
      // @ts-expect-error
      await mutate((current) => ({ ...current, ...updates }), false);
    }
    toast({ variant: "success", description: "Settings updated" });
  };

  const setSetting = async (key: SettingKey, value: string) => {
    // eslint-disable-next-line @typescript-eslint/ban-ts-comment
    // @ts-expect-error
    await mutate((current) => ({ ...current, [key]: value }), false);
  };

  return (
    <SettingsContext.Provider
      value={{ isLoading, settings: settings, setSetting, updateSettings }}
    >
      {children}
    </SettingsContext.Provider>
  );
};

export const useSettings = (): SettingsContextType => {
  const context = useContext(SettingsContext);
  if (!context) {
    throw new Error("useSettings must be used within a SettingsProvider");
  }
  return context;
};
