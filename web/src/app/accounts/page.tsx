"use client";
import { Header } from "@/components/header";
import { EmptyState } from "@/components/empty-state";
import { useSearchParams } from "next/navigation";
import Files from "@/components/files";
import useIsMobile from "@/hooks/use-is-mobile";
import { MobileHeader } from "@/components/mobile/mobile-header";
import React from "react";
import ParseLinkButton from "@/components/parse-link";
import { useI18n } from "@/hooks/use-i18n";

export default function AccountPage() {
  const { t } = useI18n();
  const isMobile = useIsMobile();
  const searchParams = useSearchParams();
  const accountId = searchParams.get("id");
  const chatId = searchParams.get("chatId");
  const messageThreadId = searchParams.get("messageThreadId")
    ? Number(searchParams.get("messageThreadId"))
    : undefined;
  const link = searchParams.get("link") ?? undefined;

  return (
    <div className="container mx-auto px-4 py-6">
      {isMobile ? <MobileHeader /> : <Header />}
      {(accountId && chatId) || (accountId && link) ? (
        <Files
          accountId={accountId}
          chatId={chatId ?? "-1"}
          messageThreadId={messageThreadId}
          link={link}
        />
      ) : (
        <div className="flex min-h-[60vh] flex-col items-center justify-center">
          <EmptyState
            hasAccounts={true}
            message={t("Select a chat to view files")}
          />
          {accountId && <ParseLinkButton accountId={accountId} />}
        </div>
      )}
    </div>
  );
}
