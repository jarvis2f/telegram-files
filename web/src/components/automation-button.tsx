"use client";
import React from "react";
import { ArrowRight } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { type TelegramChat } from "@/lib/types";
import UseIsMobile from "@/hooks/use-is-mobile";

interface AutoDownloadButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  auto?: TelegramChat["auto"];
}

const AutomationButton = React.forwardRef<
  HTMLButtonElement,
  AutoDownloadButtonProps
>(({ auto, className, ...props }, ref) => {
  const autoEnabled =
    auto &&
    (auto.preload.enabled || auto.download.enabled || auto.transfer.enabled);
  const isMobile = UseIsMobile();

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            ref={ref}
            className={cn(
              "group relative w-32 cursor-pointer overflow-hidden rounded border bg-background p-1 text-center font-semibold shadow-sm",
              isMobile && "h-10 w-full",
              className,
            )}
            {...props}
          >
            <span className="inline-block translate-x-1 transition-all duration-300 group-hover:translate-x-12 group-hover:opacity-0">
              {autoEnabled ? "Running" : "Stopped"}
            </span>
            <div className="absolute top-0 z-10 flex h-full w-full translate-x-12 items-center justify-center gap-2 text-primary-foreground opacity-0 transition-all duration-300 group-hover:-translate-x-1 group-hover:opacity-100">
              <span>{autoEnabled ? "Disable" : "Enable"}</span>
              <ArrowRight />
            </div>
            <div
              className={cn(
                "absolute left-[10%] top-[40%] h-2 w-2 scale-[1] rounded-lg bg-primary transition-all duration-300 group-hover:left-[0%] group-hover:top-[0%] group-hover:h-full group-hover:w-full group-hover:scale-[1.8] group-hover:animate-none group-hover:bg-primary",
                autoEnabled
                  ? auto?.preload.enabled &&
                    auto.download.enabled &&
                    auto.transfer.enabled
                    ? "animate-breathing bg-green-500"
                    : "animate-breathing bg-blue-500"
                  : "bg-red-500",
              )}
            ></div>
          </button>
        </TooltipTrigger>
        <TooltipContent>
          {autoEnabled
            ? "Automation is enabled, you can disable it by clicking the button"
            : "Automation is disabled, you can enable it by clicking the button"}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
});

AutomationButton.displayName = "AutoDownloadButton";

export { AutomationButton };
