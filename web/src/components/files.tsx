"use client";
import FileList from "@/components/mobile/file-list";
import { FileTable } from "@/components/file-table";
import useIsMobile from "@/hooks/use-is-mobile";

export default function Files({
  accountId,
  chatId,
}: {
  accountId: string;
  chatId: string;
}) {
  const isMobile = useIsMobile();

  if (isMobile) {
    return <FileList accountId={accountId} chatId={chatId} />;
  } else {
    return <FileTable accountId={accountId} chatId={chatId} />;
  }
}
