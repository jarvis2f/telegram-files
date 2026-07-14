"use client";

import useSWR from "swr";
import { useState } from "react";
import {
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  FileSliders,
  Loader2,
  ShieldAlert,
  Tags,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { usePlatformBindingStatus } from "@/hooks/use-platform-binding-status";
import { useShareEnabled } from "@/hooks/use-share-enabled";
import { request } from "@/lib/api";
import {
  DEFAULT_SHARE_PUBLICATION_POLICY,
  type SharePolicyRule,
  type SharePublicationPolicy,
} from "@/lib/share-publication-policy";

export function SharePublicationRules() {
  const [isCollapsed, setIsCollapsed] = useState(true);
  const shareEnabled = useShareEnabled();
  const binding = usePlatformBindingStatus();
  const canLoadPolicy = shareEnabled && binding.isBound;
  const { data, error, isLoading, mutate } = useSWR<SharePublicationPolicy>(
    canLoadPolicy ? "/share/publication-policy" : null,
    request,
    {
      fallbackData: DEFAULT_SHARE_PUBLICATION_POLICY,
      revalidateOnFocus: false,
    },
  );
  const policy = data ?? DEFAULT_SHARE_PUBLICATION_POLICY;
  const statusLabel = error ? "Policy unavailable" : "Synced policy";

  if (!canLoadPolicy) {
    return null;
  }

  if (isCollapsed) {
    return (
      <section>
        <Card
          className="cursor-pointer transition-colors hover:bg-muted/50"
          onClick={() => setIsCollapsed(false)}
        >
          <CardHeader className="py-4">
            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0">
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <Badge
                    variant={
                      error
                        ? "destructive"
                        : "secondary"
                    }
                  >
                    {statusLabel}
                  </Badge>
                  <span className="font-semibold">Sharing rules</span>
                  {isLoading && shareEnabled && (
                    <Loader2 className="size-4 animate-spin text-muted-foreground" />
                  )}
                </div>
                <p className="truncate text-sm text-muted-foreground">
                  Default {policy.defaultDecision.toLowerCase()} ·{" "}
                  {policy.categories.length} categories ·{" "}
                  {policy.shareRules.length} ordered rules
                </p>
              </div>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="h-8 w-8 shrink-0"
                aria-label="Expand sharing rules"
                onClick={(event) => {
                  event.stopPropagation();
                  setIsCollapsed(false);
                }}
              >
                <ChevronDown />
              </Button>
            </div>
          </CardHeader>
        </Card>
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div className="flex flex-col gap-1">
          <h2 className="text-xl font-semibold">Sharing rules</h2>
          <p className="max-w-2xl text-sm text-muted-foreground">
            These limits and categories are fetched from telegram-seed and are
            enforced again when a resource is published.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge
            variant={error ? "destructive" : "secondary"}
          >
            {statusLabel}
          </Badge>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-8 w-8 shrink-0"
            aria-label="Collapse sharing rules"
            onClick={() => setIsCollapsed(true)}
          >
            <ChevronUp />
          </Button>
        </div>
      </div>

      {isLoading && shareEnabled ? (
        <Card>
          <CardContent className="flex min-h-28 items-center justify-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="animate-spin" />
            Loading platform policy
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3 lg:grid-cols-[0.9fr_1.1fr]">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Tags aria-hidden="true" className="size-4" />
                Categories
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              <div className="grid gap-3 sm:grid-cols-2">
                <Fact
                  label="Default decision"
                  value={policy.defaultDecision}
                  tone={policy.defaultDecision === "ALLOW" ? "allow" : "deny"}
                />
                <Fact
                  label="Default category"
                  value={policy.defaultCategoryId}
                />
              </div>
              <div className="grid gap-2">
                {policy.categories.map((category) => (
                  <div
                    key={category.id}
                    className="flex flex-col gap-2 rounded-lg border bg-muted/20 p-3"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{category.label}</span>
                      <code className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                        {category.id}
                      </code>
                      {category.id === policy.defaultCategoryId && (
                        <Badge variant="outline">default</Badge>
                      )}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {category.defaultForFileTypes.length > 0
                        ? `Auto-selected for ${category.defaultForFileTypes.join(", ")}`
                        : "Manual selection only"}
                    </p>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <FileSliders aria-hidden="true" className="size-4" />
                Ordered rules
              </CardTitle>
            </CardHeader>
            <CardContent className="flex flex-col gap-3">
              {error && (
                <div className="rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
                  Could not refresh the platform policy. Showing the local
                  fallback until the next successful sync.
                </div>
              )}
              {policy.shareRules.length === 0 ? (
                <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
                  No explicit rules. The default decision applies to every
                  unmatched file.
                </div>
              ) : (
                policy.shareRules.map((rule, index) => (
                  <RuleRow key={rule.id} rule={rule} index={index} />
                ))
              )}
              <button
                type="button"
                className="mt-1 w-fit text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
                onClick={() => void mutate()}
              >
                Refresh rules
              </button>
            </CardContent>
          </Card>
        </div>
      )}
    </section>
  );
}

function Fact({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "allow" | "deny";
}) {
  return (
    <div className="rounded-lg border bg-muted/20 p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p
        className={
          tone === "allow"
            ? "mt-1 font-semibold text-emerald-600 dark:text-emerald-400"
            : tone === "deny"
              ? "mt-1 font-semibold text-destructive"
              : "mt-1 font-semibold"
        }
      >
        {value}
      </p>
    </div>
  );
}

function RuleRow({ rule, index }: { rule: SharePolicyRule; index: number }) {
  const isAllow = rule.decision === "ALLOW";
  const conditions = describeMatch(rule.match);

  return (
    <article className="rounded-lg border bg-muted/20 p-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant="outline">#{index + 1}</Badge>
            <code className="truncate text-xs text-muted-foreground">
              {rule.id}
            </code>
          </div>
          <p className="mt-2 text-sm font-medium">{rule.reason}</p>
        </div>
        <Badge
          className={
            isAllow
              ? "gap-1 bg-emerald-600 text-white hover:bg-emerald-600"
              : "gap-1"
          }
          variant={isAllow ? "default" : "destructive"}
        >
          {isAllow ? (
            <CheckCircle2 aria-hidden="true" className="size-3.5" />
          ) : (
            <ShieldAlert aria-hidden="true" className="size-3.5" />
          )}
          {rule.decision}
        </Badge>
      </div>
      <div className="mt-3 flex flex-wrap gap-2">
        {conditions.map((condition) => (
          <Badge key={condition} variant="secondary" className="font-normal">
            {condition}
          </Badge>
        ))}
      </div>
    </article>
  );
}

function describeMatch(match: SharePolicyRule["match"]): string[] {
  const conditions = [
    ...(match.fileTypes?.map((value) => `type: ${value}`) ?? []),
    ...(match.mimeTypes?.map((value) => `mime: ${value}`) ?? []),
    ...(match.mimeTypePrefixes?.map((value) => `mime starts ${value}`) ?? []),
    ...(match.minFileSizeBytes === undefined
      ? []
      : [`size >= ${formatBytes(match.minFileSizeBytes)}`]),
    ...(match.maxFileSizeBytes === undefined
      ? []
      : [`size <= ${formatBytes(match.maxFileSizeBytes)}`]),
  ];
  return conditions.length > 0 ? conditions : ["all files"];
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes)) return String(bytes);
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const precision = value >= 10 || unitIndex === 0 ? 0 : 1;
  return `${value.toFixed(precision)} ${units[unitIndex]}`;
}
