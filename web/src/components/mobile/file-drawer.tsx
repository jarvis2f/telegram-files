import type { TelegramFile } from "@/lib/types";
import React, { useEffect, useState } from "react";
import { Drawer, DrawerContent, DrawerTitle } from "@/components/ui/drawer";
import { cn } from "@/lib/utils";
import { VisuallyHidden } from "@radix-ui/react-visually-hidden";
import { AnimatePresence, motion } from "framer-motion";
import FileVideo from "@/components/file-video";
import FileInfo from "@/components/mobile/file-info";
import { type useFiles } from "@/hooks/use-files";
import useFileSwitch from "@/hooks/use-file-switch";
import FileImage from "../file-image";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";

type FileDrawerProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  file: TelegramFile;
  onFileChange: (file: TelegramFile) => void;
  onFileTagsClick: (file: TelegramFile) => void;
  initialViewing?: boolean;
} & ReturnType<typeof useFiles>;

export default function FileDrawer({
  open,
  onOpenChange,
  file,
  onFileChange,
  onFileTagsClick,
  initialViewing = false,
  hasMore,
  handleLoadMore,
  isLoading,
}: FileDrawerProps) {
  const [viewing, setViewing] = useState(initialViewing);

  // 防止在下载状态变化时意外调用onFileChange导致drawer关闭
  const handleFileChange = (newFile: TelegramFile) => {
    // 只在真正切换到不同文件时才调用onFileChange
    if (newFile.id !== file.id) {
      onFileChange(newFile);
    }
  };

  const { handleNavigation, direction } = useFileSwitch({
    file,
    onFileChange: handleFileChange,
    hasMore,
    handleLoadMore,
  });

  useEffect(() => {
    if (open) {
      setViewing(initialViewing);
    }
  }, [file.id, initialViewing, open]);

  useEffect(() => {
    if (
      viewing &&
      (file.downloadStatus !== "completed" ||
        (file.type !== "video" && file.type !== "photo"))
    ) {
      setViewing(false);
    }
  }, [file, viewing]);

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
          if (dx > 20) {
            handleNavigation(-1);
          } else if (dx < -20) {
            handleNavigation(1);
          }
        }

        if (viewing && Math.abs(dy) > Math.abs(dx) && dy > 0) {
          if (initialViewing) {
            onOpenChange(false);
            document.removeEventListener("touchend", handleTouchEnd);
            return;
          }
          setViewing(false);
        }
        document.removeEventListener("touchend", handleTouchEnd);
      };
      document.addEventListener("touchend", handleTouchEnd);
    };
    document.addEventListener("touchstart", handleTouchStart);
    return () => document.removeEventListener("touchstart", handleTouchStart);
  }, [handleNavigation, file, initialViewing, onOpenChange, viewing]);

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

  if (!file) return null;

  return (
    <Drawer
      open={open}
      onOpenChange={(open) => {
        if (!open && viewing) {
          if (initialViewing) {
            onOpenChange(false);
            return;
          }
          setViewing(false);
          return;
        }
        onOpenChange(open);
      }}
      handleOnly={viewing}
    >
      <DrawerContent
        data-fileid={file.id}
        data-prev={file.prev?.id}
        data-next={file.next?.id}
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
            <DotmTriangle2
              size={32}
              dotSize={4}
              speed={1.4}
              opacityBase={0.1}
              opacityMid={0.4}
              opacityPeak={0.95}
              ariaLabel="Loading file details"
            />
          </div>
        )}
        <AnimatePresence initial={false} custom={direction} mode="popLayout">
          <motion.div
            key={`${file.id}-${file.uniqueId}`}
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
                {file.type === "video" &&
                file.downloadStatus === "completed" ? (
                  <FileVideo file={file} />
                ) : (
                  <FileImage file={file} className="h-full" isFullPreview />
                )}
              </div>
            ) : (
              <FileInfo
                onView={() => setViewing(true)}
                file={file}
                onFileTagsClick={onFileTagsClick}
              />
            )}
          </motion.div>
        </AnimatePresence>
      </DrawerContent>
    </Drawer>
  );
}
