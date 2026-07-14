import "@/styles/globals.css";
import type { Metadata } from "next";
import Script from "next/script";
import React from "react";
import { Toaster } from "@/components/ui/toaster";
import { SWRProvider } from "@/components/swr-provider";
import { SettingsProvider } from "@/hooks/use-settings";
import { WebSocketProvider } from "@/hooks/use-websocket";
import { env } from "@/env";
import { TelegramAccountProvider } from "@/hooks/use-telegram-account";
import { ThemeProvider } from "@/components/theme-provider";
import { LocalStorageProvider } from "@/hooks/use-local-storage";
import { AdminSessionProvider } from "@/hooks/use-admin-session";
import { AdminAuthGate } from "@/components/admin-auth-gate";

export const metadata: Metadata = {
  title: "Telegram Files",
  description: "Manage your files on Telegram",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <link
          rel="icon"
          type="image/png"
          href="/favicon-96x96.png"
          sizes="96x96"
        />
        <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
        <link rel="shortcut icon" href="/favicon.ico" />
        <link
          rel="apple-touch-icon"
          sizes="180x180"
          href="/apple-touch-icon.png"
        />
        <meta name="apple-mobile-web-app-title" content="TeleFiles" />
        <link rel="manifest" href="/site.webmanifest" />
      </head>
      <body className="font-sans">
        {env.NEXT_PUBLIC_SCAN && (
          <Script
            src="https://unpkg.com/react-scan/dist/auto.global.js"
            strategy="afterInteractive"
          />
        )}
        <LocalStorageProvider>
          <ThemeProvider
            attribute="class"
            defaultTheme="light"
            disableTransitionOnChange
          >
            <AdminSessionProvider>
              <AdminAuthGate>
                <SWRProvider>
                  <WebSocketProvider>
                    <SettingsProvider>
                      <TelegramAccountProvider>{children}</TelegramAccountProvider>
                    </SettingsProvider>
                  </WebSocketProvider>
                </SWRProvider>
              </AdminAuthGate>
            </AdminSessionProvider>
            <Toaster />
          </ThemeProvider>
        </LocalStorageProvider>
      </body>
    </html>
  );
}
