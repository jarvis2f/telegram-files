import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { FileType } from "@/lib/types";
import useSWR from "swr";
import { Ellipsis } from "lucide-react";
import { Label } from "@/components/ui/label";
import * as React from "react";
import { useEffect } from "react";

interface FileTypeFilterProps {
  offline: boolean;
  telegramId: string;
  chatId: string;
  type: FileType | "all";
  seedOnly?: boolean;
  onChange: (type: FileType | "all") => void;
}

// 把子组件移到外部
interface FileTypeSelectItemProps {
  value: FileType;
  counts?: Record<FileType, number>;
  isLoading: boolean;
}

const FileTypeSelectItem = ({
  value,
  counts,
  isLoading,
}: FileTypeSelectItemProps) => {
  return (
    <SelectItem value={value}>
      <div className="flex items-center gap-5">
        <span className="w-10">
          {value.charAt(0).toUpperCase() + value.slice(1)}
        </span>
        {isLoading ? (
          <Ellipsis className="h-4 w-4 animate-pulse" />
        ) : (
          <span className="text-xs text-gray-400">
            {counts?.[value] ? `(${counts[value]})` : "(0)"}
          </span>
        )}
      </div>
    </SelectItem>
  );
};

export default function FileTypeFilter({
  offline,
  telegramId,
  chatId,
  type,
  seedOnly = false,
  onChange,
}: FileTypeFilterProps) {
  const [localType, setLocalType] = React.useState<FileType | "all">(type);
  const countParams = new URLSearchParams({
    offline: String(offline),
    ...(offline && seedOnly && { seedOnly: "true" }),
  });
  const { data: counts, isLoading } = useSWR<Record<FileType, number>>(
    `/telegram/${telegramId}/chat/${chatId}/files/count?${countParams.toString()}`,
  );

  const handleTypeChange = (value: FileType | "all") => {
    setLocalType(value);
    onChange(value);
  };

  useEffect(() => {
    if (!offline && localType === "all") {
      setLocalType("media");
      onChange("media");
    }
  }, [localType, offline, onChange]);

  return (
    <div className="space-y-2">
      <Label>Type</Label>
      <Select value={localType} onValueChange={handleTypeChange}>
        <SelectTrigger>
          <SelectValue placeholder="File type" />
        </SelectTrigger>
        <SelectContent>
          {offline && <SelectItem value="all">All Files</SelectItem>}
          <FileTypeSelectItem
            value="media"
            counts={counts}
            isLoading={isLoading}
          />
          <FileTypeSelectItem
            value="photo"
            counts={counts}
            isLoading={isLoading}
          />
          <FileTypeSelectItem
            value="video"
            counts={counts}
            isLoading={isLoading}
          />
          <FileTypeSelectItem
            value="audio"
            counts={counts}
            isLoading={isLoading}
          />
          <FileTypeSelectItem
            value="file"
            counts={counts}
            isLoading={isLoading}
          />
        </SelectContent>
      </Select>
    </div>
  );
}
