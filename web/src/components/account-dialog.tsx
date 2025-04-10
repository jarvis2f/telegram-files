"use client";

import { type ReactNode, useEffect, useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import ProxysDialog from "@/components/proxys-dialog";
import AccountCreator from "@/components/account-creator";
import { toast } from "@/hooks/use-toast";

export function AccountDialog({
  children,
  isAdd,
}: {
  children: ReactNode;
  isAdd?: boolean;
}) {
  const [proxyName, setProxyName] = useState<string | undefined>();
  const { account } = useTelegramAccount();
  const [newAccountId, setNewAccountId] = useState<string | undefined>();
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (account) {
      setProxyName(account.proxy);
    }
  }, [account]);

  return (
    <Dialog
      open={open}
      onOpenChange={(open) => {
        if (isAdd) {
          setNewAccountId(undefined);
          setProxyName(undefined);
        }
        setOpen(open);
      }}
    >
      <DialogTrigger asChild>{children}</DialogTrigger>
      <DialogContent
        aria-describedby={undefined}
        className="h-full w-full md:h-auto md:min-h-40 md:min-w-[550px]"
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            Add Telegram Account
            {(account ?? newAccountId) && (
              <p className="rounded-md bg-gray-100 p-1 text-xs text-muted-foreground dark:bg-gray-800 dark:text-gray-300">
                {account ? account.id : newAccountId}
              </p>
            )}
          </DialogTitle>
        </DialogHeader>
        <div className="flex flex-col">
          <AccountCreator
            isAdd={isAdd}
            proxyName={proxyName}
            onCreated={setNewAccountId}
            onLoginSuccess={() => setOpen(false)}
          />
          <div className="flex justify-end">
            <ProxysDialog
              telegramId={account ? account.id : newAccountId}
              proxyName={proxyName}
              onProxyNameChange={(proxyName) => {
                // Only set with new account
                setProxyName(proxyName);
                toast({
                  title: "Success",
                  description: proxyName
                    ? `Proxy is set to ${proxyName} with new account`
                    : "Proxy is disabled",
                });
              }}
              enableSelect={true}
              className="mt-3"
            />
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
