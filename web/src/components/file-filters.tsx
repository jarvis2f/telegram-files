import { type DownloadStatus, type FileFilter } from "@/lib/types";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import TableColumnFilter, {
  type Column,
} from "@/components/table-column-filter";
import {
  type RowHeight,
  TableRowHeightSwitch,
} from "@/components/table-row-height-switch";
import FileTypeFilter from "@/components/file-type-filter";

interface FileFiltersProps {
  telegramId: string;
  chatId: string;
  filters: FileFilter;
  onFiltersChange: (filters: FileFilter) => void;
  columns: Column[];
  onColumnConfigChange: (config: Column[]) => void;
  rowHeight: RowHeight;
  setRowHeight: (e: RowHeight) => void;
}

export function FileFilters({
  telegramId,
  chatId,
  filters,
  onFiltersChange,
  columns,
  onColumnConfigChange,
  rowHeight,
  setRowHeight,
}: FileFiltersProps) {
  return (
    <div className="mb-6 flex flex-col justify-between md:flex-row">
      <div className="flex flex-col gap-4 md:flex-row">
        <Input
          placeholder="Search files..."
          value={filters.search}
          onChange={(e) =>
            onFiltersChange({ ...filters, search: e.target.value })
          }
          className="md:w-[300px]"
        />

        <FileTypeFilter
          telegramId={telegramId}
          chatId={chatId}
          type={filters.type}
          onTypeChange={(type) => onFiltersChange({ ...filters, type })}
        />

        <Select
          value={filters.status}
          onValueChange={(value) =>
            onFiltersChange({
              ...filters,
              status: value as DownloadStatus | "all",
            })
          }
        >
          <SelectTrigger className="w-[150px]">
            <SelectValue placeholder="Download status" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All statuses</SelectItem>
            <SelectItem value="idle">Not downloaded</SelectItem>
            <SelectItem value="downloading">Downloading</SelectItem>
            <SelectItem value="completed">Completed</SelectItem>
            <SelectItem value="error">Error</SelectItem>
          </SelectContent>
        </Select>
      </div>

      <div className="flex gap-4">
        <TableColumnFilter
          columns={columns}
          onColumnConfigChange={onColumnConfigChange}
        />
        {!!rowHeight && !!setRowHeight && (
          <TableRowHeightSwitch
            rowHeight={rowHeight}
            setRowHeightAction={setRowHeight}
          />
        )}
      </div>
    </div>
  );
}