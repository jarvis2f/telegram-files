"use client";

import * as React from "react";
import * as ProgressPrimitive from "@radix-ui/react-progress";

import { cn } from "@/lib/utils";

type ProgressProps = React.ComponentPropsWithoutRef<
  typeof ProgressPrimitive.Root
> & {
  indicatorClassName?: string;
  variant?: "default" | "download";
};

const Progress = React.forwardRef<
  React.ComponentRef<typeof ProgressPrimitive.Root>,
  ProgressProps
>(
  (
    { className, indicatorClassName, value, variant = "default", ...props },
    ref,
  ) => {
    const normalizedValue = Math.min(Math.max(value ?? 0, 0), 100);

    return (
      <ProgressPrimitive.Root
        ref={ref}
        className={cn(
          "relative h-2 w-full overflow-hidden rounded-full bg-primary/20",
          variant === "download" && "download-progress h-1.5",
          className,
        )}
        value={normalizedValue}
        {...props}
      >
        <ProgressPrimitive.Indicator
          className={cn(
            "h-full w-full flex-1 bg-primary transition-transform duration-300 ease-out",
            variant === "download" && "download-progress__indicator",
            indicatorClassName,
          )}
          style={{ transform: `translateX(-${100 - normalizedValue}%)` }}
        />
      </ProgressPrimitive.Root>
    );
  },
);
Progress.displayName = ProgressPrimitive.Root.displayName;

export { Progress };
