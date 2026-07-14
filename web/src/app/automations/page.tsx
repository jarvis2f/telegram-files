"use client";

import { AutomationOverview } from "@/components/automation-overview";
import { PlatformTelegramIcon } from "@/components/platform-telegram-icon";
import ThemeToggleButton from "@/components/theme-toggle-button";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { ArrowLeft } from "lucide-react";
import Link from "next/link";

export default function AutomationsPage() {
  return (
    <div className="container mx-auto px-4 py-6">
      <Card className="mb-6">
        <CardContent className="p-4">
          <div className="relative flex items-center justify-between gap-4">
            <Button variant="ghost" size="sm" asChild>
              <Link href="/">
                <ArrowLeft data-icon="inline-start" />
                Home
              </Link>
            </Button>

            <div className="flex min-w-0 items-center gap-2">
              <PlatformTelegramIcon className="size-6 shrink-0" />
              <h3 className="truncate text-lg font-semibold">
                Automation Overview
              </h3>
            </div>

            <ThemeToggleButton />
          </div>
        </CardContent>
      </Card>

      <AutomationOverview />
    </div>
  );
}
