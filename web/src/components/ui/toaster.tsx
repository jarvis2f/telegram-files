"use client";

import { TOAST_VARIANTS, useToast } from "@/hooks/use-toast";
import {
  Toast,
  ToastClose,
  ToastDescription,
  ToastProvider,
  ToastTitle,
  ToastViewport,
} from "@/components/ui/toast";
import useIsMobile from "@/hooks/use-is-mobile";
import { type ElementType } from "react";

export function Toaster() {
  const { toasts } = useToast();
  const isMobile = useIsMobile();

  return (
    <ToastProvider duration={isMobile ? 1000 : 5000}>
      {toasts.map(function ({
        variant = "default",
        id,
        title,
        description,
        action,
        ...props
      }) {
        const toastStyle = TOAST_VARIANTS[variant] || TOAST_VARIANTS.default;
        const IconComponent = toastStyle.icon as unknown as ElementType;

        return (
          <Toast variant={variant} key={id} {...props}>
            <div
              className="flex w-full items-start pr-4"
              onClick={(e) => e.stopPropagation()}
            >
              {IconComponent && (
                <div
                  className={`mr-3 flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-transform duration-200 group-hover:scale-105 ${toastStyle.iconColor}`}
                >
                  <IconComponent className="h-4 w-4" />
                </div>
              )}

              <div className="min-w-0 flex-1 space-y-1">
                {title && <ToastTitle>{title}</ToastTitle>}
                {description && (
                  <ToastDescription>{description}</ToastDescription>
                )}

                {action && <div className="pt-1.5">{action}</div>}
              </div>
              <ToastClose onClick={(e) => e.stopPropagation()} />
            </div>
          </Toast>
        );
      })}
      <ToastViewport />
    </ToastProvider>
  );
}
