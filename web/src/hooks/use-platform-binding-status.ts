"use client";

import useSWR from "swr";
import { useShareEnabled } from "@/hooks/use-share-enabled";

export type PlatformBindingStatus = {
  status:
    | "UNBOUND"
    | "BOUND"
    | "pending"
    | "slow_down"
    | "denied"
    | "expired"
    | "cancelled"
    | "error";
  nodeName?: string;
};

export function usePlatformBindingStatus() {
  const shareEnabled = useShareEnabled();
  const result = useSWR<PlatformBindingStatus, Error>(
    shareEnabled ? "/share/device/status" : null,
    {
      refreshInterval: 10_000,
      revalidateOnFocus: true,
      shouldRetryOnError: false,
      onError: () => undefined,
    },
  );

  return {
    ...result,
    isBound: shareEnabled ? result.data?.status === "BOUND" : false,
    isPending: shareEnabled
      ? result.data?.status === "pending" || result.data?.status === "slow_down"
      : false,
  };
}
