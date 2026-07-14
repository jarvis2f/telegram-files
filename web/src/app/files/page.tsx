"use client";
import Files from "@/components/files";
import { Card, CardContent } from "@/components/ui/card";
import ThemeToggleButton from "@/components/theme-toggle-button";
import Link from "next/link";
import { PlatformTelegramIcon } from "@/components/platform-telegram-icon";

export default function AccountPage() {
  return (
    <div className="container mx-auto px-4 py-6">
      <Card className="mb-6">
        <CardContent className="p-4">
          <div className="relative flex items-center justify-between gap-4">
            <Link href={"/"} className="inline-flex">
              <PlatformTelegramIcon className="size-6" />
            </Link>

            <h3 className="text-lg font-semibold">Telegram Files Manager</h3>

            <ThemeToggleButton />
          </div>
        </CardContent>
      </Card>
      <Files accountId="-1" chatId="-1" />
    </div>
  );
}
