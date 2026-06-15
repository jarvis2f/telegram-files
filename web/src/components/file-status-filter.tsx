import {
  Select,
  SelectContent,
  SelectGroup,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import * as SelectPrimitive from "@radix-ui/react-select";
import { cn } from "@/lib/utils";
import type { DownloadStatus, TransferStatus } from "@/lib/types";
import { Check } from "lucide-react";
import * as React from "react";
import { useMemo } from "react";
import { DOWNLOAD_STATUS, TRANSFER_STATUS } from "@/components/file-status";
import useIsMobile from "@/hooks/use-is-mobile";
import { Label } from "@/components/ui/label";

const statusOptions = {
  downloadStatus: {
    text: "Download Status",
    status: DOWNLOAD_STATUS,
  },
  transferStatus: {
    text: "Transfer Status",
    status: TRANSFER_STATUS,
  },
};

interface FileStatusFilterProps {
  downloadStatus: DownloadStatus | undefined;
  transferStatus: TransferStatus | undefined;
  onChange: (
    downloadStatus?: DownloadStatus,
    transferStatus?: TransferStatus,
  ) => void;
}

export default function FileStatusFilter({
  downloadStatus,
  transferStatus,
  onChange,
}: FileStatusFilterProps) {
  const isMobile = useIsMobile();
  const [localDownloadStatus, setLocalDownloadStatus] = React.useState<
    DownloadStatus | undefined
  >(downloadStatus);
  const [localTransferStatus, setLocalTransferStatus] = React.useState<
    TransferStatus | undefined
  >(transferStatus);

  const handleStatusSelect = (value: string) => {
    const [group, item] = value.split("||");

    let downloadStatus = localDownloadStatus;
    let transferStatus = localTransferStatus;
    if (group === "downloadStatus") {
      const updatedDownloadStatus =
        localDownloadStatus === item ? undefined : (item as DownloadStatus);
      setLocalDownloadStatus(updatedDownloadStatus);
      downloadStatus = updatedDownloadStatus;
    } else if (group === "transferStatus") {
      const updatedTransferStatus =
        localTransferStatus === item ? undefined : (item as TransferStatus);
      setLocalTransferStatus(updatedTransferStatus);
      transferStatus = updatedTransferStatus;
    }
    onChange(downloadStatus, transferStatus);
  };

  const statusDisplayValue = useMemo(() => {
    if (!localDownloadStatus && !localTransferStatus) {
      return "Filter Status";
    }
    const downloadStatus =
      localDownloadStatus && DOWNLOAD_STATUS[localDownloadStatus];
    const transferStatus =
      localTransferStatus && TRANSFER_STATUS[localTransferStatus];

    return (
      <div className="flex items-center space-x-2">
        {downloadStatus && (
          <div className="flex items-center space-x-1 rounded bg-gray-100 p-1 dark:bg-gray-800">
            <downloadStatus.icon className="h-3 w-3" />
            <span className="text-xs">{downloadStatus.text}</span>
          </div>
        )}
        {transferStatus && (
          <div className="flex items-center space-x-1 rounded bg-gray-100 p-1 dark:bg-gray-800">
            <transferStatus.icon className="h-3 w-3" />
            <span className="text-xs">{transferStatus.text}</span>
          </div>
        )}
      </div>
    );
  }, [localDownloadStatus, localTransferStatus]);

  return (
    <div className="space-y-2">
      <Label>Status</Label>
      <Select
        value={`${localDownloadStatus ?? ""}||${localTransferStatus ?? ""}`}
        onValueChange={(value) => handleStatusSelect(value)}
      >
        <SelectTrigger>
          <SelectValue placeholder="Filter Status">
            {statusDisplayValue}
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          {Object.entries(statusOptions).map(([groupKey, group]) => (
            <SelectGroup key={groupKey}>
              <SelectLabel>{group.text}</SelectLabel>
              {Object.entries(group.status).map(([statusKey, item]) => (
                <SelectPrimitive.Item
                  key={`${groupKey}||${statusKey}`}
                  value={`${groupKey}||${statusKey}`}
                  className={cn(
                    "relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-2 pr-8 text-sm outline-none hover:bg-accent hover:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
                    !isMobile && "focus:bg-accent focus:text-accent-foreground",
                  )}
                >
                  {(groupKey === "downloadStatus"
                    ? localDownloadStatus
                    : localTransferStatus) === statusKey && (
                    <span className="absolute right-2 flex h-3.5 w-3.5 items-center justify-center">
                      <Check className="h-4 w-4" />
                    </span>
                  )}
                  <SelectPrimitive.ItemText>
                    <div className="flex items-center">
                      {item.icon && <item.icon className="mr-2 h-4 w-4" />}
                      {item.text}
                    </div>
                  </SelectPrimitive.ItemText>
                </SelectPrimitive.Item>
              ))}
            </SelectGroup>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
