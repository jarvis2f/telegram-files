import {
  type DownloadStatus,
  type FileFilter,
  type TransferStatus,
} from "@/lib/types";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import TableColumnFilter, {
  type Column,
} from "@/components/table-column-filter";
import { type RowHeight } from "@/components/table-row-height-switch";
import FileTypeFilter from "@/components/file-type-filter";
import dynamic from "next/dynamic";
import { useDebouncedCallback } from "use-debounce";
import * as React from "react";
import { useEffect, useMemo, useState } from "react";
import { Check, X } from "lucide-react";
import { Button } from "./ui/button";
import { DOWNLOAD_STATUS, TRANSFER_STATUS } from "@/components/file-status";
import * as SelectPrimitive from "@radix-ui/react-select";
import { cn } from "@/lib/utils";
import useIsMobile from "@/hooks/use-is-mobile";

interface FileFiltersProps {
  telegramId: string;
  chatId: string;
  filters: FileFilter;
  onFiltersChange: (filters: FileFilter) => void;
  columns?: Column[];
  onColumnConfigChange?: (config: Column[]) => void;
  rowHeight?: RowHeight;
  setRowHeight?: (e: RowHeight) => void;
}

const TableRowHeightSwitch = dynamic(
  () =>
    import("@/components/table-row-height-switch").then(
      (mod) => mod.TableRowHeightSwitch,
    ),
  { ssr: false },
);

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

export function FileFilters({
  telegramId,
  chatId,
  filters,
  onFiltersChange,
  columns = [],
  onColumnConfigChange = () => void 0,
  rowHeight = "m",
  setRowHeight = () => void 0,
}: FileFiltersProps) {
  const isMobile = useIsMobile();
  const [search, setSearch] = useState(filters.search);

  const handleSearchChange = useDebouncedCallback((search: string) => {
    onFiltersChange({ ...filters, search });
  }, 500);

  const handleStatusSelect = (value: string) => {
    const [group, item] = value.split("||");

    if (group === "downloadStatus") {
      onFiltersChange({
        ...filters,
        downloadStatus:
          item === filters.downloadStatus
            ? undefined
            : (item as DownloadStatus),
      });
    } else if (group === "transferStatus") {
      onFiltersChange({
        ...filters,
        transferStatus:
          item === filters.transferStatus
            ? undefined
            : (item as TransferStatus),
      });
    }
  };

  const statusDisplayValue = useMemo(() => {
    if (!filters.downloadStatus && !filters.transferStatus) {
      return "Filter Status";
    }
    const downloadStatus =
      filters.downloadStatus && DOWNLOAD_STATUS[filters.downloadStatus];
    const transferStatus =
      filters.transferStatus && TRANSFER_STATUS[filters.transferStatus];

    return (
      <div className="flex items-center space-x-2">
        {downloadStatus && (
          <div className="flex items-center space-x-1 rounded bg-gray-100 p-1 dark:bg-gray-800">
            <span className="text-xs">Download</span>
            <downloadStatus.icon className="h-3 w-3" />
          </div>
        )}
        {transferStatus && (
          <div className="flex items-center space-x-1 rounded bg-gray-100 p-1 dark:bg-gray-800">
            <span className="text-xs">Transfer</span>
            <transferStatus.icon className="h-3 w-3" />
          </div>
        )}
      </div>
    );
  }, [filters.downloadStatus, filters.transferStatus]);

  useEffect(() => {
    handleSearchChange(search);
  }, [search, handleSearchChange]);

  return (
    <div className="mb-6 flex flex-col justify-between md:flex-row">
      <div className="grid grid-cols-3 gap-4 md:flex md:flex-row">
        <div className="relative col-span-3">
          <Input
            placeholder="Search files..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="relative md:w-[300px]"
          />
          {search && (
            <Button
              variant="ghost"
              size="icon"
              className="absolute right-2 top-1/2 h-6 w-6 -translate-y-1/2 rounded-full text-gray-500 transition-all duration-200 hover:scale-110 hover:bg-gray-100 hover:text-gray-800"
              onClick={() => setSearch("")}
            >
              <X className="h-4 w-4" />
            </Button>
          )}
        </div>

        <FileTypeFilter
          telegramId={telegramId}
          chatId={chatId}
          type={filters.type}
          onTypeChange={(type) => onFiltersChange({ ...filters, type })}
        />

        <Select
          value={`${filters.downloadStatus ?? ""}||${filters.transferStatus ?? ""}`}
          onValueChange={(value) => handleStatusSelect(value)}
        >
          <SelectTrigger className="col-span-2 w-full md:w-[210px]">
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
                      "relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-2 pr-8 text-sm outline-none hover:bg-accent hover:text-accent-foreground focus:bg-accent focus:text-accent-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
                    )}
                  >
                    {filters[groupKey as keyof FileFilter] === statusKey && (
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

      {!isMobile && (
        <div className="hidden gap-4 md:flex">
          <TableColumnFilter
            columns={columns}
            onColumnConfigChange={onColumnConfigChange}
          />
          <TableRowHeightSwitch
            rowHeight={rowHeight}
            setRowHeightAction={setRowHeight}
          />
        </div>
      )}
    </div>
  );
}
