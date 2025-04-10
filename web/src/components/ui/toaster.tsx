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
              className="flex items-start"
              onClick={(e) => e.stopPropagation()}
            >
              {IconComponent && (
                <div className={`mr-3 flex-shrink-0 ${toastStyle.iconColor}`}>
                  <IconComponent size={20} />
                </div>
              )}

              <div className="min-w-0 flex-1">
                {title && (
                  <ToastTitle className="mb-1 font-medium">{title}</ToastTitle>
                )}
                {description && (
                  <ToastDescription className="text-sm opacity-90">
                    {description}
                  </ToastDescription>
                )}

                {action}
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
