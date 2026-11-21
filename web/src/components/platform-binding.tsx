"use client";

import {
  Activity,
  ArrowUpRight,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  Cloud,
  Copy,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
  Unlink,
} from "lucide-react";
import QRCodeStyling from "qr-code-styling";
import {
  type FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import { request } from "@/lib/api";
import { useI18n } from "@/hooks/use-i18n";

type BindingState =
  | "UNBOUND"
  | "BOUND"
  | "pending"
  | "slow_down"
  | "denied"
  | "expired"
  | "cancelled"
  | "error";

interface BindingStatus {
  status: BindingState;
  userCode?: string;
  verificationUri?: string;
  verificationUriComplete?: string;
  expiresAt?: number;
  interval?: number;
  nodeId?: string;
  nodeName?: string;
  platformUrl?: string;
  tokenExpireAt?: number;
  lastHeartbeatAt?: number | null;
}

const terminalStates = new Set<BindingState>([
  "denied",
  "expired",
  "cancelled",
  "error",
]);

function defaultNodeName(): string {
  if (typeof window === "undefined") return "Telegram Files node";
  return `${window.location.hostname} · Telegram Files`;
}

export function PlatformBinding() {
  const { t } = useI18n();
  const [status, setStatus] = useState<BindingStatus>({ status: "UNBOUND" });
  const [nodeName, setNodeName] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isMutating, setIsMutating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => setNodeName(defaultNodeName()), []);

  const loadStatus = useCallback(async () => {
    try {
      const next = await request<BindingStatus>("/share/device/status");
      setStatus(next);
      if (next.nodeName) setNodeName(next.nodeName);
      setError(null);
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadStatus();
  }, [loadStatus]);

  useEffect(() => {
    if (status.status !== "pending" && status.status !== "slow_down") return;
    const interval = window.setInterval(
      () => void loadStatus(),
      Math.max(2, status.interval ?? 5) * 1_000,
    );
    return () => window.clearInterval(interval);
  }, [loadStatus, status.interval, status.status]);

  useEffect(() => {
    if (!status.expiresAt) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [status.expiresAt]);

  const mutate = async (operation: () => Promise<unknown>) => {
    setIsMutating(true);
    setError(null);
    try {
      await operation();
      await loadStatus();
    } catch (failure) {
      setError(messageOf(failure));
    } finally {
      setIsMutating(false);
    }
  };

  const authorize = (event: FormEvent) => {
    event.preventDefault();
    void mutate(() =>
      request("/share/device/authorize", {
        method: "POST",
        body: JSON.stringify({ nodeName }),
      }),
    );
  };

  const rename = (event: FormEvent) => {
    event.preventDefault();
    void mutate(() =>
      request("/share/node/name", {
        method: "PUT",
        body: JSON.stringify({ nodeName }),
      }),
    );
  };

  if (isLoading) {
    return (
      <Card>
        <CardContent className="flex min-h-80 items-center justify-center p-6">
          <LoaderCircle className="animate-spin text-muted-foreground" />
          <span className="sr-only">{t("Loading platform binding")}</span>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      {error && (
        <div
          role="alert"
          className="rounded-lg border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          {t(error)}
        </div>
      )}

      {status.status === "BOUND" ? (
        <BoundCard
          status={status}
          nodeName={nodeName}
          setNodeName={setNodeName}
          isMutating={isMutating}
          onRename={rename}
          onUnbind={() =>
            void mutate(() => request("/share/node", { method: "DELETE" }))
          }
        />
      ) : status.status === "pending" || status.status === "slow_down" ? (
        <PendingCard
          status={status}
          now={now}
          isMutating={isMutating}
          onRefresh={() => void loadStatus()}
          onCancel={() =>
            void mutate(() =>
              request("/share/device/cancel", { method: "POST" }),
            )
          }
        />
      ) : (
        <AuthorizeCard
          status={status.status}
          nodeName={nodeName}
          setNodeName={setNodeName}
          isMutating={isMutating}
          onSubmit={authorize}
        />
      )}
    </div>
  );
}

function AuthorizeCard({
  status,
  nodeName,
  setNodeName,
  isMutating,
  onSubmit,
}: {
  status: BindingState;
  nodeName: string;
  setNodeName: (value: string) => void;
  isMutating: boolean;
  onSubmit: (event: FormEvent) => void;
}) {
  const { t } = useI18n();

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-4">
          <div className="flex flex-col gap-2">
            <Badge variant="secondary" className="w-fit gap-1.5 px-2 py-1">
              <Cloud data-icon="inline-start" size={16} />
              {t("Unbound")}
            </Badge>
            <CardTitle className="text-2xl">{t("Connect this node")}</CardTitle>
            <CardDescription className="max-w-xl">
              {t(
                "Authorize this installation from Telegram Seed. Credentials stay encrypted on this machine and can be revoked independently.",
              )}
            </CardDescription>
          </div>
          <ShieldCheck className="text-muted-foreground" aria-hidden="true" />
        </div>
      </CardHeader>
      <CardContent>
        <form
          id="platform-authorize"
          onSubmit={onSubmit}
          className="flex flex-col gap-2"
        >
          <Label htmlFor="node-name">{t("Node name")}</Label>
          <Input
            id="node-name"
            value={nodeName}
            maxLength={128}
            required
            autoComplete="off"
            onChange={(event) => setNodeName(event.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            {t(
              "Use a name that makes this machine easy to recognize in your device list.",
            )}
          </p>
          {terminalStates.has(status) && (
            <p className="mt-2 text-sm text-muted-foreground">
              {t(
                "The previous authorization was {status}. Start a new request when ready.",
                { status: t(status) },
              )}
            </p>
          )}
        </form>
      </CardContent>
      <CardFooter>
        <Button
          type="submit"
          form="platform-authorize"
          disabled={isMutating || !nodeName.trim()}
        >
          {isMutating ? (
            <LoaderCircle data-icon="inline-start" className="animate-spin" />
          ) : (
            <ArrowUpRight data-icon="inline-start" />
          )}
          {t("Start authorization")}
        </Button>
      </CardFooter>
    </Card>
  );
}

function PendingCard({
  status,
  now,
  isMutating,
  onRefresh,
  onCancel,
}: {
  status: BindingStatus;
  now: number;
  isMutating: boolean;
  onRefresh: () => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const remaining = Math.max(0, (status.expiresAt ?? now) - now);
  const total = 10 * 60 * 1_000;
  const progress = Math.min(100, (remaining / total) * 100);

  return (
    <Card>
      <CardHeader>
        <Badge variant="secondary" className="w-fit gap-1.5 px-2 py-1">
          <Activity data-icon="inline-start" size={16} />
          {t("Waiting for approval")}
        </Badge>
        <CardTitle className="text-2xl">
          {t("Approve in Telegram Seed")}
        </CardTitle>
        <CardDescription>
          {t(
            "Scan the code or open the approval link, then confirm the node name and scopes.",
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="grid gap-6 md:grid-cols-[12rem_1fr] md:items-center">
        <BindingQrCode value={status.verificationUriComplete} />
        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium uppercase tracking-widest text-muted-foreground">
              {t("User code")}
            </span>
            <div className="flex flex-wrap items-center gap-2">
              <code className="rounded-md border bg-muted px-3 py-2 text-xl font-semibold tracking-widest">
                {status.userCode}
              </code>
              <Button
                type="button"
                size="icon"
                variant="outline"
                aria-label={t("Copy user code")}
                onClick={() =>
                  void navigator.clipboard.writeText(status.userCode ?? "")
                }
              >
                <Copy />
              </Button>
            </div>
          </div>
          <div className="flex flex-col gap-2">
            <div className="flex justify-between gap-4 text-xs text-muted-foreground">
              <span>{t("Authorization window")}</span>
              <span>{formatRemaining(remaining)}</span>
            </div>
            <Progress value={progress} />
          </div>
          {status.verificationUriComplete && (
            <Button asChild variant="outline" className="w-fit">
              <a
                href={status.verificationUriComplete}
                target="_blank"
                rel="noreferrer"
              >
                <ArrowUpRight data-icon="inline-start" />
                {t("Open approval page")}
              </a>
            </Button>
          )}
        </div>
      </CardContent>
      <CardFooter className="justify-between gap-3 border-t pt-6">
        <Button type="button" variant="ghost" onClick={onRefresh}>
          <RefreshCw data-icon="inline-start" />
          {t("Check now")}
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={isMutating}
          onClick={onCancel}
        >
          {t("Cancel")}
        </Button>
      </CardFooter>
    </Card>
  );
}

function BoundCard({
  status,
  nodeName,
  setNodeName,
  isMutating,
  onRename,
  onUnbind,
}: {
  status: BindingStatus;
  nodeName: string;
  setNodeName: (value: string) => void;
  isMutating: boolean;
  onRename: (event: FormEvent) => void;
  onUnbind: () => void;
}) {
  const { t, locale } = useI18n();
  const [isCollapsed, setIsCollapsed] = useState(true);
  const lastHeartbeat = formatRelative(
    status.lastHeartbeatAt,
    locale,
    t("Not reported yet"),
  );

  if (isCollapsed) {
    return (
      <Card
        className="cursor-pointer transition-colors hover:bg-muted/50"
        onClick={() => setIsCollapsed(false)}
      >
        <CardHeader className="py-4">
          <div className="flex items-center justify-between gap-4">
            <div className="flex flex-wrap items-center gap-3">
              <Badge className="w-fit gap-1.5 px-2 py-1">
                <CheckCircle2 data-icon="inline-start" size={16} />
                {t("Bound")}
              </Badge>
              <span className="font-semibold">{status.nodeName}</span>
              <span className="hidden font-mono text-xs text-muted-foreground sm:inline">
                {shortId(status.nodeId, t("Node ID pending"))}
              </span>
              <span className="hidden text-xs text-muted-foreground md:inline">
                {t("• Last heartbeat {time}", { time: lastHeartbeat })}
              </span>
            </div>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("Show node details")}
              className="h-8 w-8 shrink-0"
              onClick={(e) => {
                e.stopPropagation();
                setIsCollapsed(false);
              }}
            >
              <ChevronDown size={16} />
            </Button>
          </div>
        </CardHeader>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="flex flex-col gap-2">
            <Badge className="w-fit gap-1.5 px-2 py-1">
              <CheckCircle2 data-icon="inline-start" size={16} />
              {t("Bound")}
            </Badge>
            <CardTitle className="text-2xl">{status.nodeName}</CardTitle>
            <CardDescription>
              {t(
                "This node has its own credential family and heartbeat identity.",
              )}
            </CardDescription>
          </div>
          <div className="flex items-start gap-4">
            <span className="mt-2 font-mono text-xs text-muted-foreground">
              {shortId(status.nodeId, t("Node ID pending"))}
            </span>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label={t("Hide node details")}
              className="-mr-2 -mt-2 h-8 w-8 shrink-0 text-muted-foreground"
              onClick={() => setIsCollapsed(true)}
            >
              <ChevronUp size={16} />
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <div className="grid gap-4 sm:grid-cols-3">
          <Fact
            label={t("Platform")}
            value={hostOf(status.platformUrl, t("Unknown"))}
          />
          <Fact label={t("Last heartbeat")} value={lastHeartbeat} />
          <Fact
            label={t("Access rotates")}
            value={formatRelative(
              status.tokenExpireAt,
              locale,
              t("Not reported yet"),
            )}
          />
        </div>
        <Separator />
        <form
          id="platform-rename"
          onSubmit={onRename}
          className="flex flex-col gap-2"
        >
          <Label htmlFor="bound-node-name">{t("Node name")}</Label>
          <div className="flex flex-col gap-2 sm:flex-row">
            <Input
              id="bound-node-name"
              value={nodeName}
              maxLength={128}
              required
              onChange={(event) => setNodeName(event.target.value)}
            />
            <Button
              type="submit"
              variant="outline"
              disabled={
                isMutating ||
                !nodeName.trim() ||
                nodeName.trim() === status.nodeName
              }
            >
              {t("Save name")}
            </Button>
          </div>
        </form>
      </CardContent>
      <CardFooter className="justify-end border-t pt-6">
        <Button
          type="button"
          variant="destructive"
          disabled={isMutating}
          onClick={onUnbind}
        >
          {isMutating ? (
            <LoaderCircle data-icon="inline-start" className="animate-spin" />
          ) : (
            <Unlink data-icon="inline-start" />
          )}
          {t("Unbind this node")}
        </Button>
      </CardFooter>
    </Card>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border bg-muted/30 p-4">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className="truncate text-sm font-medium">{value}</span>
    </div>
  );
}

function BindingQrCode({ value }: { value?: string }) {
  const { t } = useI18n();
  const host = useRef<HTMLDivElement>(null);
  const qrCode = useMemo(
    () =>
      value
        ? new QRCodeStyling({
            width: 176,
            height: 176,
            type: "svg",
            data: value,
            margin: 8,
            qrOptions: { errorCorrectionLevel: "M" },
            dotsOptions: { type: "rounded" },
            cornersSquareOptions: { type: "extra-rounded" },
          })
        : null,
    [value],
  );

  useEffect(() => {
    const element = host.current;
    if (!element || !qrCode) return;
    element.replaceChildren();
    qrCode.append(element);
    return () => element.replaceChildren();
  }, [qrCode]);

  return (
    <div className="flex min-h-48 items-center justify-center rounded-xl border bg-white p-2">
      {value ? (
        <div ref={host} />
      ) : (
        <LoaderCircle
          className="animate-spin text-muted-foreground"
          role="status"
          aria-label={t("Loading QR code")}
        />
      )}
    </div>
  );
}

function messageOf(failure: unknown): string {
  return failure instanceof Error
    ? failure.message
    : "The platform request failed";
}

function shortId(value: string | undefined, pending: string): string {
  if (!value) return pending;
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
}

function hostOf(value: string | undefined, unknown: string): string {
  if (!value) return unknown;
  try {
    return new URL(value).host;
  } catch {
    return value;
  }
}

function formatRelative(
  value: number | null | undefined,
  locale: string,
  notReported: string,
): string {
  if (!value) return notReported;
  const distance = value - Date.now();
  const absolute = Math.abs(distance);
  const formatter = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  if (absolute < 60_000)
    return formatter.format(Math.round(distance / 1_000), "second");
  if (absolute < 3_600_000)
    return formatter.format(Math.round(distance / 60_000), "minute");
  return formatter.format(Math.round(distance / 3_600_000), "hour");
}

function formatRemaining(milliseconds: number): string {
  const seconds = Math.ceil(milliseconds / 1_000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}
