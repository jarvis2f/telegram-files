import { useCallback, useState } from "react";
import { toast } from "@/hooks/use-toast";
import type { TelegramFile } from "@/lib/types";
import { useI18n } from "@/hooks/use-i18n";

type FileSwitchProps = {
  file: TelegramFile;
  onFileChange: (file: TelegramFile) => void;
  hasMore: boolean;
  handleLoadMore: () => Promise<void>;
};

export default function useFileSwitch({
  file,
  onFileChange,
  hasMore,
  handleLoadMore,
}: FileSwitchProps) {
  const { t } = useI18n();
  const [direction, setDirection] = useState(0);

  const handleNavigation = useCallback(
    (newDirection: number) => {
      const newFile = newDirection > 0 ? file?.next : file?.prev;
      if (newFile) {
        setDirection(newDirection);
        onFileChange(newFile);
      } else if (newDirection > 0) {
        if (hasMore) {
          void handleLoadMore();
        } else {
          toast({
            variant: "info",
            description: t("No more files to load"),
          });
        }
      }
    },
    [file?.next, file?.prev, onFileChange, hasMore, handleLoadMore, t],
  );

  return {
    direction,
    handleNavigation,
  };
}
