import { type TelegramFile } from "@/lib/types";
import PhotoPreview from "@/components/photo-preview";
import React, { type ReactNode, useCallback, useEffect, useState } from "react";
import {
  Dialog,
  DialogOverlay,
  DialogPortal,
  DialogTitle,
  DialogTrigger,
} from "./ui/dialog";
import { VisuallyHidden } from "@radix-ui/react-visually-hidden";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { cn } from "@/lib/utils";
import VideoPreview from "./video-preview";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";

interface FileViewerProps {
  file: TelegramFile;
  children: ReactNode;
}

export default function FileViewer({ file, children }: FileViewerProps) {
  const [open, setOpen] = useState(false);
  const [innerFile, setInnerFile] = useState<TelegramFile | undefined>(file);
  const [direction, setDirection] = useState(0);

  const handleNavigation = useCallback(
    (newDirection: number) => {
      setDirection(newDirection);
      setInnerFile(newDirection > 0 ? innerFile?.next : innerFile?.prev);
    },
    [innerFile],
  );

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (innerFile === undefined || !open) return;

      if (e.key === "ArrowLeft" && innerFile.prev) {
        handleNavigation(-1);
      } else if (e.key === "ArrowRight" && innerFile.next) {
        handleNavigation(1);
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [handleNavigation, innerFile, open]);

  const slideVariants = {
    enter: (direction: number) => ({
      x: direction > 0 ? 500 : -500,
      opacity: 0,
    }),
    center: {
      zIndex: 1,
      x: 0,
      opacity: 1,
    },
    exit: (direction: number) => ({
      zIndex: 0,
      x: direction < 0 ? 500 : -500,
      opacity: 0,
    }),
  };

  if (!innerFile) return <>{children}</>;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <div
          className="cursor-pointer"
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
            setOpen(true);
          }}
        >
          {children}
        </div>
      </DialogTrigger>
      <DialogPortal>
        <DialogOverlay>
          <>
            {innerFile.prev && (
              <div
                className="fixed bottom-0 left-0 top-0 flex h-full w-20 cursor-pointer items-center justify-center opacity-0 hover:opacity-100"
                onClick={() => handleNavigation(-1)}
              >
                <ChevronLeft className="h-10 w-10 text-white hover:text-accent" />
              </div>
            )}

            {innerFile.next && (
              <div
                className="fixed bottom-0 right-0 top-0 flex h-full w-20 cursor-pointer items-center justify-center opacity-0 hover:opacity-100"
                onClick={() => handleNavigation(1)}
              >
                <ChevronRight className="h-10 w-10 text-white hover:text-accent" />
              </div>
            )}
          </>
        </DialogOverlay>

        <DialogPrimitive.Content
          data-fileid={innerFile.id}
          data-prev={innerFile.prev?.id}
          data-next={innerFile.next?.id}
          className={cn(
            "fixed left-[50%] top-[50%] z-50 translate-x-[-50%] translate-y-[-50%] duration-200 focus-visible:outline-none data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[state=closed]:slide-out-to-left-1/2 data-[state=closed]:slide-out-to-top-[48%] data-[state=open]:slide-in-from-left-1/2 data-[state=open]:slide-in-from-top-[48%]",
          )}
          aria-describedby={undefined}
          onInteractOutside={(e) => {
            if (e.target instanceof Element) {
              if (e.target.getAttribute("data-state")) {
                setOpen(false);
                setInnerFile(file);
              }
            }
            e.preventDefault();
          }}
        >
          <div className="relative">
            <AnimatePresence initial={false} custom={direction} mode="popLayout">
              <motion.div
                key={innerFile.id}
                custom={direction}
                variants={slideVariants}
                initial="enter"
                animate="center"
                exit="exit"
                transition={{
                  x: { type: "spring", stiffness: 300, damping: 30 },
                  opacity: { duration: 0.2 },
                }}
                className="mx-auto"
                style={{
                  maxWidth: "calc(100vw - 100px)",
                  maxHeight: "100vh",
                }}
              >
                <VisuallyHidden>
                  <DialogTitle>File Viewer</DialogTitle>
                </VisuallyHidden>
                {innerFile.type === "video" &&
                innerFile.downloadStatus === "completed" ? (
                  <VideoPreview file={innerFile} />
                ) : (
                  <PhotoPreview file={innerFile} />
                )}
              </motion.div>
            </AnimatePresence>
          </div>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  );
}
