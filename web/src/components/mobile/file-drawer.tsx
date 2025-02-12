import type { TelegramFile } from "@/lib/types";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "@/hooks/use-toast";
import { Drawer, DrawerContent, DrawerTitle } from "@/components/ui/drawer";
import { cn } from "@/lib/utils";
import { VisuallyHidden } from "@radix-ui/react-visually-hidden";
import { LoaderPinwheel, Minimize2 } from "lucide-react";
import { AnimatePresence, motion } from "framer-motion";
import { Button } from "@/components/ui/button";
import VideoPreview from "@/components/video-preview";
import PhotoPreview from "@/components/photo-preview";
import FileInfo from "@/components/mobile/file-info";
import { type useFiles } from "@/hooks/use-files";

type FileDrawerProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  file: TelegramFile;
} & ReturnType<typeof useFiles>;

export default function FileDrawer({
  open,
  onOpenChange: setOpen,
  file,
  hasMore,
  handleLoadMore,
  isLoading,
}: FileDrawerProps) {
  const [innerFile, setInnerFile] = useState<TelegramFile | undefined>(file);
  const [direction, setDirection] = useState(0);
  const [viewing, setViewing] = useState(false);
  const prevFileRef = useRef(file);

  const handleNavigation = useCallback(
    (newDirection: number) => {
      const newFile = newDirection > 0 ? innerFile?.next : innerFile?.prev;
      if (newFile) {
        setDirection(newDirection);
        setInnerFile(newFile);
        prevFileRef.current = newFile;
      } else if (newDirection > 0) {
        if (hasMore) {
          console.log("Loading more files", handleLoadMore);
          void handleLoadMore?.();
        } else {
          toast({
            description: "No more files to load",
          });
        }
      }
    },
    [hasMore, innerFile?.next, innerFile?.prev, handleLoadMore],
  );

  useEffect(() => {
    if (file.id !== prevFileRef.current.id) {
      let newDirection = 0;
      if (prevFileRef.current.next?.id === file.id) {
        newDirection = 1;
      } else if (prevFileRef.current.prev?.id === file.id) {
        newDirection = -1;
      }

      setDirection(newDirection);
      setInnerFile(file);
      prevFileRef.current = file;
    }
  }, [file]);

  useEffect(() => {
    if (!open) {
      setViewing(false);
      if (innerFile?.id !== file.id) {
        setInnerFile(file);
        setDirection(0);
      }
    }
  }, [open, file, innerFile?.id]);

  useEffect(() => {
    if (!innerFile) {
      setViewing(false);
      setOpen(false);
    } else {
      if (
        viewing &&
        (innerFile.downloadStatus !== "completed" ||
          (innerFile.type !== "video" && innerFile.type !== "photo"))
      ) {
        setViewing(false);
      }
    }
  }, [innerFile, viewing]);

  useEffect(() => {
    const handleTouchStart = (e: TouchEvent) => {
      const touch = e.touches[0];
      if (!touch) return;
      const x = touch.clientX;
      const y = touch.clientY;

      const handleTouchEnd = (e: TouchEvent) => {
        const touch = e.changedTouches[0];
        if (!touch) return;
        const dx = touch.clientX - x;
        const dy = touch.clientY - y;

        if (Math.abs(dx) > Math.abs(dy)) {
          if (dx > 0) {
            handleNavigation(-1);
          } else if (dx < 0) {
            handleNavigation(1);
          }
        }
        document.removeEventListener("touchend", handleTouchEnd);
      };
      document.addEventListener("touchend", handleTouchEnd);
    };
    document.addEventListener("touchstart", handleTouchStart);
    return () => document.removeEventListener("touchstart", handleTouchStart);
  }, [handleNavigation, innerFile]);

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

  if (!innerFile) return null;

  return (
    <Drawer
      open={open}
      onOpenChange={(open) => {
        if (!open && viewing) {
          setViewing(false);
          return;
        }
        setOpen(open);
      }}
    >
      <DrawerContent
        data-fileid={innerFile.id}
        data-prev={innerFile.prev?.id}
        data-next={innerFile.next?.id}
        className={cn(
          "focus:outline-none",
          viewing && "rounded-none border-none",
        )}
        aria-describedby={undefined}
      >
        <VisuallyHidden>
          <DrawerTitle>File Details</DrawerTitle>
        </VisuallyHidden>
        {isLoading && (
          <div className="fixed inset-0 z-[100] flex items-center justify-center">
            <LoaderPinwheel
              className="h-8 w-8 animate-spin"
              style={{ strokeWidth: "0.8px" }}
            />
          </div>
        )}
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
            style={{
              maxWidth: "100vw",
              maxHeight: "100vh",
            }}
          >
            {viewing ? (
              <div className="relative flex min-h-screen items-center justify-center">
                <div className="absolute left-2 top-2 z-10">
                  <Button
                    onClick={() => setViewing(false)}
                    variant="ghost"
                    size="icon"
                  >
                    <Minimize2
                      className={cn(
                        "h-8 w-8",
                        innerFile.type === "video" &&
                          innerFile.downloadStatus === "completed" &&
                          "text-white",
                      )}
                    />
                  </Button>
                </div>
                {innerFile.type === "video" &&
                innerFile.downloadStatus === "completed" ? (
                  <VideoPreview file={innerFile} />
                ) : (
                  <PhotoPreview file={innerFile} className="h-full" />
                )}
              </div>
            ) : (
              <FileInfo onView={() => setViewing(true)} file={innerFile} />
            )}
          </motion.div>
        </AnimatePresence>
      </DrawerContent>
    </Drawer>
  );
}
