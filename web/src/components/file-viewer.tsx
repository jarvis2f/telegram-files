import { type TelegramFile } from "@/lib/types";
import React, { useEffect, useState } from "react";
import { Dialog, DialogOverlay, DialogPortal, DialogTitle } from "./ui/dialog";
import { VisuallyHidden } from "@radix-ui/react-visually-hidden";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { cn } from "@/lib/utils";
import FileVideo from "./file-video";
import { ChevronLeft, ChevronRight, CircleX } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import { type useFiles } from "@/hooks/use-files";
import FileExtra from "@/components/file-extra";
import { Button } from "@/components/ui/button";
import useFileSwitch from "@/hooks/use-file-switch";
import FileImage from "./file-image";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";

type FileViewerProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  file: TelegramFile;
  onFileChange: (file: TelegramFile) => void;
} & ReturnType<typeof useFiles>;

export default function FileViewer({
  open,
  onOpenChange,
  onFileChange,
  file,
  hasMore,
  handleLoadMore,
  isLoading,
}: FileViewerProps) {
  const [showVideoChrome, setShowVideoChrome] = useState(true);
  const [isVideoChromeHovered, setIsVideoChromeHovered] = useState(false);
  const { handleNavigation, direction } = useFileSwitch({
    file,
    onFileChange,
    hasMore,
    handleLoadMore,
  });

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (file === undefined || !open) return;

      if (e.key === "ArrowLeft") {
        handleNavigation(-1);
      } else if (e.key === "ArrowRight") {
        handleNavigation(1);
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [handleNavigation, file, open]);

  const slideVariants = {
    enter: (direction: number) => ({
      x: direction > 0 ? 1000 : -1000,
      opacity: 0,
    }),
    center: {
      zIndex: 1,
      x: 0,
      opacity: 1,
    },
    exit: (direction: number) => ({
      zIndex: 0,
      x: direction < 0 ? 1000 : -1000,
      opacity: 0,
    }),
  };

  const isPlayableVideo =
    file?.type === "video" && file.downloadStatus === "completed";
  const showPreviewChrome =
    !isPlayableVideo || showVideoChrome || isVideoChromeHovered;

  useEffect(() => {
    setShowVideoChrome(true);
    setIsVideoChromeHovered(false);
  }, [file?.id, open]);

  if (!file) return null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogPortal>
        <DialogOverlay className="bg-black/90 backdrop-blur-sm" />

        <DialogPrimitive.Content
          data-fileid={file.id}
          data-prev={file.prev?.id}
          data-next={file.next?.id}
          className={cn(
            "fixed inset-0 z-50 h-dvh w-dvw duration-200 focus-visible:outline-none data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0",
          )}
          aria-describedby={undefined}
          onInteractOutside={(e) => {
            if (e.target instanceof Element) {
              if (e.target.getAttribute("data-state")) {
                onOpenChange(false);
              }
            }
            e.preventDefault();
          }}
        >
          <AnimatePresence>
            {showPreviewChrome && (
              <>
                <motion.div
                  initial={isPlayableVideo ? { opacity: 0, y: -16 } : false}
                  animate={{ opacity: 1, y: 0 }}
                  exit={isPlayableVideo ? { opacity: 0, y: -16 } : undefined}
                  transition={{ duration: 0.18 }}
                  onMouseEnter={() => setIsVideoChromeHovered(true)}
                  onMouseLeave={() => setIsVideoChromeHovered(false)}
                  className="fixed left-0 top-0 z-[120] flex w-dvw items-center justify-between bg-gradient-to-b from-black/80 via-black/45 to-transparent px-3 py-3 text-white sm:px-5 sm:py-4"
                >
                  <div className="min-w-0 rounded-md border border-white/10 bg-white/10 px-3 py-2 shadow-2xl backdrop-blur-md">
                    <FileExtra file={file} rowHeight="s" ellipsis />
                  </div>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="Close file preview"
                    onClick={() => onOpenChange(false)}
                    className="rounded-full border border-white/10 bg-white/10 text-white shadow-2xl backdrop-blur-md hover:bg-white/20 hover:text-white [&_svg]:size-5"
                  >
                    <CircleX />
                  </Button>
                </motion.div>
                {file.prev && (
                  <motion.div
                    initial={isPlayableVideo ? { opacity: 0, x: -16 } : false}
                    animate={{ opacity: 1, x: 0 }}
                    exit={isPlayableVideo ? { opacity: 0, x: -16 } : undefined}
                    transition={{ duration: 0.18 }}
                    className="fixed bottom-0 left-3 top-0 z-[110] my-auto hidden h-12 w-12 sm:block"
                  >
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Previous file"
                      className="h-12 w-12 rounded-full border border-white/10 bg-white/10 text-white opacity-70 shadow-2xl backdrop-blur-md transition hover:bg-white/20 hover:text-white hover:opacity-100 [&_svg]:size-6"
                      onClick={() => handleNavigation(-1)}
                    >
                      <ChevronLeft />
                    </Button>
                  </motion.div>
                )}

                {(file.next ?? hasMore) && (
                  <motion.div
                    initial={isPlayableVideo ? { opacity: 0, x: 16 } : false}
                    animate={{ opacity: 1, x: 0 }}
                    exit={isPlayableVideo ? { opacity: 0, x: 16 } : undefined}
                    transition={{ duration: 0.18 }}
                    className="fixed bottom-0 right-3 top-0 z-[110] my-auto hidden h-12 w-12 sm:block"
                  >
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="Next file"
                      className="h-12 w-12 rounded-full border border-white/10 bg-white/10 text-white opacity-70 shadow-2xl backdrop-blur-md transition hover:bg-white/20 hover:text-white hover:opacity-100 [&_svg]:size-6"
                      onClick={() => handleNavigation(1)}
                    >
                      <ChevronRight />
                    </Button>
                  </motion.div>
                )}
              </>
            )}
          </AnimatePresence>
          <div
            className={cn(
              "relative flex min-h-dvh items-center justify-center",
              isPlayableVideo ? "p-0" : "px-3 py-20 sm:px-20",
            )}
          >
            <AnimatePresence
              initial={false}
              custom={direction}
              mode="popLayout"
            >
              <motion.div
                key={file.id}
                custom={direction}
                variants={slideVariants}
                initial="enter"
                animate="center"
                exit="exit"
                transition={{
                  x: { type: "spring", stiffness: 300, damping: 30 },
                  opacity: { duration: 0.5 },
                }}
                className={cn(
                  "mx-auto flex items-center justify-center",
                  isPlayableVideo && "h-dvh w-dvw",
                )}
                style={{
                  maxWidth: isPlayableVideo
                    ? "100dvw"
                    : "calc(100vw - 1.5rem)",
                  maxHeight: isPlayableVideo
                    ? "100dvh"
                    : "calc(100vh - 8rem)",
                }}
              >
                <VisuallyHidden>
                  <DialogTitle>File Viewer</DialogTitle>
                </VisuallyHidden>
                {isPlayableVideo ? (
                  <FileVideo
                    file={file}
                    onControlsVisibilityChange={setShowVideoChrome}
                  />
                ) : (
                  <FileImage
                    file={file}
                    className="max-h-[calc(100vh-8rem)] rounded-md shadow-2xl ring-1 ring-white/10"
                    isFullPreview
                  />
                )}
              </motion.div>
            </AnimatePresence>
          </div>
          {isLoading && (
            <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/20 backdrop-blur-[1px]">
              <div className="rounded-full border border-white/10 bg-black/40 p-4 shadow-2xl backdrop-blur-md">
                <DotmTriangle2
                  size={32}
                  dotSize={4}
                  speed={1.4}
                  opacityBase={0.1}
                  opacityMid={0.4}
                  opacityPeak={0.95}
                  ariaLabel="Loading file preview"
                />
              </div>
            </div>
          )}
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  );
}
