import { Header } from "@/components/header";
import Files from "@/components/files";

export default async function ChatFilesPage({
  params,
}: {
  params: Promise<{ accountId: string; chatId: string }>;
}) {
  const { accountId, chatId } = await params;

  return (
    <div className="container mx-auto px-4 py-6">
      <Header />
      <Files accountId={accountId} chatId={chatId} />
    </div>
  );
}
