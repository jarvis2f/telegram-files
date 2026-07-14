"use client";

import { BadgeCheck, LoaderCircle, Network, Unplug } from "lucide-react";
import Link from "next/link";

import { Button } from "@/components/ui/button";
import { TooltipWrapper } from "@/components/ui/tooltip";
import { usePlatformBindingStatus } from "@/hooks/use-platform-binding-status";
import { useShareEnabled } from "@/hooks/use-share-enabled";

export function PlatformBindingShortcut() {
  const shareEnabled = useShareEnabled();
  const { data, error, isLoading, isBound, isPending } =
    usePlatformBindingStatus();

  if (!shareEnabled) {
    const disabledLabel = "Sharing feature is disabled";
    return (
      <TooltipWrapper content={disabledLabel}>
        <span className="inline-flex cursor-not-allowed">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            disabled
            aria-label={disabledLabel}
            className="pointer-events-none opacity-50"
          >
            <Unplug data-icon="inline-start" />
          </Button>
        </span>
      </TooltipWrapper>
    );
  }

  let label = "Platform node not bound";
  let icon = <Unplug data-icon="inline-start" />;

  if (isLoading) {
    label = "Checking platform node binding";
    icon = <LoaderCircle data-icon="inline-start" className="animate-spin" />;
  } else if (error || !data) {
    label = "Platform node binding status unavailable";
    icon = <Network data-icon="inline-start" />;
  } else if (isBound) {
    label = data.nodeName
      ? `Platform node bound: ${data.nodeName}`
      : "Platform node bound";
    icon = <BadgeCheck data-icon="inline-start" />;
  } else if (isPending) {
    label = "Platform node authorization pending";
    icon = <LoaderCircle data-icon="inline-start" className="animate-spin" />;
  }

  return (
    <TooltipWrapper content={label}>
      <Button asChild type="button" variant="ghost" size="icon">
        <Link href="/share" aria-label={label}>
          {icon}
        </Link>
      </Button>
    </TooltipWrapper>
  );
}
