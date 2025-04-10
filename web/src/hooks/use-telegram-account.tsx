"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useToast } from "@/hooks/use-toast";
import { createContext, useContext, useEffect, useMemo, useState } from "react";
import type { TelegramAccount } from "@/lib/types";
import useSWR from "swr";
import useSWRMutation from "swr/mutation";
import { POST } from "@/lib/api";

interface TelegramAccountContextType {
  isLoading: boolean;
  getAccounts: (status?: "active" | "inactive") => TelegramAccount[];
  accountId: string | undefined;
  account?: TelegramAccount;
  handleAccountChange: (accountId: string) => void;
  resetAccount: () => void;
}

const TelegramAccountContext = createContext<
  TelegramAccountContextType | undefined
>(undefined);

interface TelegramAccountProviderProps {
  children: React.ReactNode;
}

export const TelegramAccountProvider: React.FC<
  TelegramAccountProviderProps
> = ({ children }) => {
  const {
    data: accounts,
    isLoading,
    isValidating,
  } = useSWR<TelegramAccount[]>(`/telegrams`);
  const router = useRouter();
  const { toast } = useToast();
  const searchParams = useSearchParams();
  const routerAccountId = searchParams.get("id") ?? undefined;
  const [accountId, setAccountId] = useState<string | undefined>(
    routerAccountId,
  );

  const { trigger: triggerChange } = useSWRMutation(
    "/telegrams/change",
    (key, { arg }: { arg: { accountId: string | undefined } }) => {
      return POST(`${key}?telegramId=${arg.accountId}`);
    },
  );

  useEffect(() => {
    if (accountId) {
      void triggerChange({ accountId });
    }
  }, [accountId, triggerChange]);

  const account = useMemo(
    () => accounts?.find((a) => a.id === accountId),
    [accountId, accounts],
  );

  const getAccounts = (status?: "active" | "inactive") => {
    if (!status) return accounts ?? [];
    return accounts?.filter((a) => a.status === status) ?? [];
  };

  const handleAccountChange = (newAccountId: string) => {
    if (newAccountId === accountId && routerAccountId) {
      return;
    }
    const account = accounts?.find((a) => a.id === newAccountId);
    if (!account) return;
    setAccountId(newAccountId);
    if (account.status === "active") {
      toast({
        variant: "info",
        title: "Account Changed",
        description: `Switched to ${accounts?.find((a) => a.id === newAccountId)?.name}'s account`,
      });
      router.push(`/accounts?id=${newAccountId}`);
    }
  };

  const handleAccountReset = () => {
    void triggerChange({ accountId: "" }).then(() => setAccountId(undefined));
  };

  return (
    <TelegramAccountContext.Provider
      value={{
        isLoading: isLoading || isValidating,
        getAccounts,
        accountId,
        account,
        handleAccountChange,
        resetAccount: handleAccountReset,
      }}
    >
      {children}
    </TelegramAccountContext.Provider>
  );
};

export function useTelegramAccount() {
  const context = useContext(TelegramAccountContext);
  if (!context) {
    throw new Error(
      "useTelegramAccount must be used within a TelegramAccountProvider",
    );
  }
  return context;
}
