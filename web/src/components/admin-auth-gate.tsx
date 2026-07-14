"use client";

import { type FormEvent, type ReactNode, useState } from "react";
import Image from "next/image";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAdminSession } from "@/hooks/use-admin-session";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";

export function AdminAuthGate({ children }: { children: ReactNode }) {
  const { status, session, sessionExpired, login, bootstrap } =
    useAdminSession();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [bootstrapToken, setBootstrapToken] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (status === "loading") {
    return (
      <main className="grid min-h-screen place-items-center bg-muted/30">
        <div className="flex flex-col items-center gap-3 text-muted-foreground">
          <DotmTriangle2
            size={32}
            dotSize={4}
            speed={1.4}
            opacityBase={0.1}
            opacityMid={0.4}
            opacityPeak={0.95}
            ariaLabel="Checking administrator session"
          />
        </div>
      </main>
    );
  }

  if (status === "authenticated" && session) {
    return children;
  }

  const bootstrapMode = status === "bootstrap";

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (bootstrapMode) {
        await bootstrap(bootstrapToken, username, password);
      } else {
        await login(username, password);
      }
      setPassword("");
      setBootstrapToken("");
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : "Authentication failed",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="grid min-h-screen place-items-center bg-muted/30 px-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex justify-center pb-2">
            <Image
              src="/favicon.svg"
              alt="Telegram Files logo"
              width={72}
              height={72}
              priority
            />
          </div>
          <CardTitle className="text-center">
            {bootstrapMode
              ? "Create the first administrator"
              : "Administrator sign in"}
          </CardTitle>
          {bootstrapMode && (
            <CardDescription className="text-center">
              Use the one-time code printed by the local API process. The
              first administrator can be created from loopback or the same
              private LAN.
            </CardDescription>
          )}
        </CardHeader>
        <CardContent>
          <form className="space-y-4" onSubmit={submit}>
            {sessionExpired && (
              <p
                role="alert"
                className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
              >
                Your session expired or was revoked. Sign in again.
              </p>
            )}
            {bootstrapMode && (
              <div className="space-y-2">
                <Label htmlFor="bootstrap-token">One-time bootstrap code</Label>
                <Input
                  id="bootstrap-token"
                  name="bootstrapToken"
                  autoComplete="off"
                  value={bootstrapToken}
                  onChange={(event) => setBootstrapToken(event.target.value)}
                  required
                />
              </div>
            )}
            <div className="space-y-2">
              <Label htmlFor="admin-username">Username</Label>
              <Input
                id="admin-username"
                name="username"
                autoComplete="username"
                minLength={3}
                maxLength={64}
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="admin-password">Password</Label>
              <Input
                id="admin-password"
                name="password"
                type="password"
                autoComplete={
                  bootstrapMode ? "new-password" : "current-password"
                }
                minLength={12}
                maxLength={256}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
              />
            </div>
            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}
            <Button className="w-full" type="submit" disabled={busy}>
              {busy
                ? "Please wait…"
                : bootstrapMode
                  ? "Create administrator"
                  : "Sign in"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </main>
  );
}
