import * as React from "react";
import { useEffect, useRef, useState } from "react";
import { format } from "date-fns";
import {
  ArrowDownNarrowWide,
  ArrowUpNarrowWide,
  Calendar as CalendarRange,
  ChevronDown,
  ChevronUp,
  Filter,
  GalleryHorizontal,
  List,
  RotateCcw,
  Sparkles,
  X,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  type DownloadStatus,
  type FileFilter,
  type FileType,
  type SortFields,
  type TransferStatus,
} from "@/lib/types";
import { Button } from "./ui/button";
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerFooter,
  DrawerTitle,
  DrawerTrigger,
} from "./ui/drawer";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import { Calendar } from "@/components/ui/calendar";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { RangeSlider } from "@/components/ui/slider";
import { cn, split } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import FileTypeFilter from "@/components/file-type-filter";
import FileStatusFilter from "@/components/file-status-filter";
import { Switch } from "@/components/ui/switch";
import useIsMobile from "@/hooks/use-is-mobile";
import { TagsSelector } from "@/components/ui/tags-selector";
import { useSettings } from "@/hooks/use-settings";
import { Toggle } from "@/components/ui/toggle";
import { useLocalStorage } from "@/hooks/use-local-storage";

const SearchFilter = ({
  search,
  onChange,
}: {
  search: string;
  onChange: (search: string) => void;
}) => {
  const [localSearch, setLocalSearch] = useState(search);

  const handleChange = (value: string) => {
    setLocalSearch(value);
    onChange(value);
  };

  return (
    <div className="space-y-2">
      <Label>Keyword</Label>
      <div className="relative">
        <Input
          placeholder="Search with name or caption"
          value={localSearch}
          onChange={(e) => handleChange(e.target.value)}
        />
        {search && (
          <Button
            variant="ghost"
            size="icon"
            className="absolute right-2 top-1/2 h-6 w-6 -translate-y-1/2 rounded-full text-gray-500 transition-all duration-200 hover:scale-110 hover:bg-gray-100 hover:text-gray-800"
            onClick={() => handleChange("")}
          >
            <X className="h-4 w-4" />
          </Button>
        )}
      </div>
    </div>
  );
};

interface TagsFilterProps {
  tags: string[];
  onChange: (tags: string[]) => void;
}

const TagsFilter = ({ tags, onChange }: TagsFilterProps) => {
  const { settings } = useSettings();

  return (
    <div className="space-y-2">
      <Label>Tags</Label>
      <TagsSelector
        value={tags}
        onChangeAction={onChange}
        tags={split(",", settings?.tags)}
      />
    </div>
  );
};

interface DateFilterProps {
  dateType: "sent" | "downloaded" | undefined;
  dateRange: [string, string] | undefined;
  onChange: (type: "sent" | "downloaded", range: [string, string]) => void;
}

const DateFilter = ({ dateType, dateRange, onChange }: DateFilterProps) => {
  const [open, setOpen] = useState(false);
  const isMobile = useIsMobile();
  const [localType, setLocalType] = useState<"sent" | "downloaded">(
    dateType ?? "sent",
  );
  const [localRange, setLocalRange] = useState<
    [Date | undefined, Date | undefined]
  >([
    dateRange?.[0] ? new Date(dateRange[0]) : undefined,
    dateRange?.[1] ? new Date(dateRange[1]) : undefined,
  ]);

  const handleTypeChange = (type: "sent" | "downloaded") => {
    setLocalType(type);
  };

  const handleRangeSelect = (range?: {
    from: Date | undefined;
    to?: Date | undefined;
  }) => {
    if (!range) return;

    setLocalRange([range.from, range.to]);
    if (range.from && range.to) {
      onChange(localType, [
        format(range.from, "yyyy-MM-dd"),
        format(range.to, "yyyy-MM-dd"),
      ]);
    }
  };

  const getDisplayText = () => {
    if (!dateRange?.[0] && !dateRange?.[1]) return "Select date range";
    if (dateRange[0] && dateRange[1]) {
      return `${format(new Date(dateRange[0]), "LLL dd, y")} - ${format(new Date(dateRange[1]), "LLL dd, y")}`;
    }
    return "Date range selected";
  };

  return (
    <div className="space-y-2">
      <Label>Date Filter</Label>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            variant="outline"
            className="w-full justify-start text-left font-normal"
          >
            <CalendarRange className="mr-2 h-4 w-4" />
            <span className="flex-1">{getDisplayText()}</span>
            <span className="ml-2 rounded bg-zinc-100 px-2 py-0.5 text-xs text-zinc-600">
              {localType === "downloaded" ? "Download" : "Sent"}
            </span>
          </Button>
        </PopoverTrigger>
        <PopoverContent
          className="w-auto p-4"
          side={isMobile ? undefined : "right"}
          modal={true}
        >
          <div className="space-y-4">
            <div className="flex gap-2">
              <Button
                size="sm"
                variant={localType === "sent" ? "default" : "outline"}
                onClick={() => handleTypeChange("sent")}
                className="flex-1"
              >
                Sent Date
              </Button>
              <Button
                size="sm"
                variant={localType === "downloaded" ? "default" : "outline"}
                onClick={() => handleTypeChange("downloaded")}
                className="flex-1"
              >
                Downloaded
              </Button>
            </div>
            <div className="rounded-md border p-2">
              <Calendar
                mode="range"
                selected={{
                  from: localRange[0],
                  to: localRange[1],
                }}
                onSelect={handleRangeSelect}
                numberOfMonths={2}
                defaultMonth={localRange[0] ?? new Date()}
              />
            </div>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
};

interface SizeFilterProps {
  sizeRange: [number, number] | undefined;
  sizeUnit: "KB" | "MB" | "GB" | undefined;
  onChange: (range: [number, number], unit: "KB" | "MB" | "GB") => void;
}

type SizeUnit = "KB" | "MB" | "GB";

interface UnitConfig {
  max: number;
  step: number;
  label: string;
}

const UNIT_CONFIGS: Record<SizeUnit, UnitConfig> = {
  KB: { max: 1024, step: 10, label: "KB" },
  MB: { max: 4000, step: 10, label: "MB" },
  GB: { max: 4, step: 0.1, label: "GB" },
};

const PRESETS: {
  label: string;
  unit: SizeUnit;
  range: [number, number];
  description?: string;
  isPremium?: boolean;
}[] = [
  { label: "全部", unit: "GB", range: [0, 4] },
  { label: "< 10 MB", unit: "MB", range: [0, 10] },
  { label: "10-100 MB", unit: "MB", range: [10, 100] },
  { label: "100 MB-2 GB", unit: "MB", range: [100, 2000], description: "普通上限" },
  { label: "2 GB-4 GB", unit: "GB", range: [2, 4], isPremium: true, description: "Premium 专属" },
];

const SizeFilter = ({ sizeRange, sizeUnit, onChange }: SizeFilterProps) => {
  const [isExpanded, setIsExpanded] = useState<boolean>(false);
  const [localUnit, setLocalUnit] = useState<SizeUnit>(sizeUnit ?? "MB");
  const [localRange, setLocalRange] = useState<[number, number]>(() => {
    if (sizeRange) return sizeRange;
    const unit = sizeUnit ?? "MB";
    return [0, UNIT_CONFIGS[unit].max];
  });

  const [minInput, setMinInput] = useState<string>(
    (sizeRange?.[0] ?? 0).toString(),
  );
  const [maxInput, setMaxInput] = useState<string>(
    (sizeRange?.[1] ?? UNIT_CONFIGS[sizeUnit ?? "MB"].max).toString(),
  );

  useEffect(() => {
    const unit = sizeUnit ?? "MB";
    setLocalUnit(unit);
    const range: [number, number] = sizeRange ?? [0, UNIT_CONFIGS[unit].max];
    setLocalRange(range);
    setMinInput(range[0].toString());
    setMaxInput(range[1].toString());
  }, [sizeRange, sizeUnit]);

  const config = UNIT_CONFIGS[localUnit];

  const convertValue = (val: number, from: SizeUnit, to: SizeUnit): number => {
    if (from === to) return val;
    let bytes = val;
    if (from === "KB") bytes = val * 1024;
    else if (from === "MB") bytes = val * 1024 * 1024;
    else if (from === "GB") bytes = val * 1024 * 1024 * 1024;

    let res = bytes;
    if (to === "KB") res = bytes / 1024;
    else if (to === "MB") res = bytes / (1024 * 1024);
    else if (to === "GB") res = bytes / (1024 * 1024 * 1024);

    const max = UNIT_CONFIGS[to].max;
    if (to === "GB") {
      res = Math.round(res * 10) / 10;
    } else {
      res = Math.round(res);
    }
    return Math.min(Math.max(0, res), max);
  };

  const handleSliderChange = (newValue: number[]) => {
    const range: [number, number] = [newValue[0]!, newValue[1]!];
    setLocalRange(range);
    setMinInput(range[0].toString());
    setMaxInput(range[1].toString());
    onChange(range, localUnit);
  };

  const handleUnitChange = (newUnit: SizeUnit) => {
    if (newUnit === localUnit) return;
    const newMin = convertValue(localRange[0], localUnit, newUnit);
    let newMax = convertValue(localRange[1], localUnit, newUnit);
    if (newMax <= newMin) {
      newMax = UNIT_CONFIGS[newUnit].max;
    }
    const newRange: [number, number] = [newMin, newMax];
    setLocalUnit(newUnit);
    setLocalRange(newRange);
    setMinInput(newMin.toString());
    setMaxInput(newMax.toString());
    onChange(newRange, newUnit);
  };

  const handleApplyPreset = (preset: (typeof PRESETS)[number]) => {
    setLocalUnit(preset.unit);
    setLocalRange(preset.range);
    setMinInput(preset.range[0].toString());
    setMaxInput(preset.range[1].toString());
    onChange(preset.range, preset.unit);
  };

  const handleMinInputChange = (valStr: string) => {
    setMinInput(valStr);
    const num = parseFloat(valStr);
    if (!isNaN(num)) {
      const clampedMin = Math.max(0, Math.min(num, localRange[1]));
      const newRange: [number, number] = [clampedMin, localRange[1]];
      setLocalRange(newRange);
      onChange(newRange, localUnit);
    }
  };

  const handleMaxInputChange = (valStr: string) => {
    setMaxInput(valStr);
    const num = parseFloat(valStr);
    if (!isNaN(num)) {
      const clampedMax = Math.min(config.max, Math.max(num, localRange[0]));
      const newRange: [number, number] = [localRange[0], clampedMax];
      setLocalRange(newRange);
      onChange(newRange, localUnit);
    }
  };

  const handleInputBlur = () => {
    let minVal = parseFloat(minInput);
    let maxVal = parseFloat(maxInput);

    if (isNaN(minVal) || minVal < 0) minVal = 0;
    if (isNaN(maxVal) || maxVal > config.max) maxVal = config.max;

    if (minVal > maxVal) {
      minVal = maxVal;
    }

    const newRange: [number, number] = [minVal, maxVal];
    setLocalRange(newRange);
    setMinInput(minVal.toString());
    setMaxInput(maxVal.toString());
    onChange(newRange, localUnit);
  };

  const handleReset = () => {
    const defaultUnit: SizeUnit = "MB";
    const defaultRange: [number, number] = [0, UNIT_CONFIGS[defaultUnit].max];
    setLocalUnit(defaultUnit);
    setLocalRange(defaultRange);
    setMinInput("0");
    setMaxInput(defaultRange[1].toString());
    onChange(defaultRange, defaultUnit);
  };

  const isPresetActive = (p: (typeof PRESETS)[number]) => {
    return (
      localUnit === p.unit &&
      localRange[0] === p.range[0] &&
      localRange[1] === p.range[1]
    );
  };

  return (
    <div className="space-y-2">
      <div
        className="flex items-center justify-between cursor-pointer select-none py-1"
        onClick={() => setIsExpanded((prev) => !prev)}
      >
        <Label className="cursor-pointer font-medium">File Size Range</Label>
        <div className="flex items-center gap-1.5">
          <span className="text-xs text-muted-foreground font-normal">
            {localRange[0]} - {localRange[1]} {localUnit}
          </span>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-6 w-6 p-0 text-muted-foreground hover:text-foreground"
            onClick={(e) => {
              e.stopPropagation();
              setIsExpanded((prev) => !prev);
            }}
          >
            {isExpanded ? (
              <ChevronUp className="h-4 w-4" />
            ) : (
              <ChevronDown className="h-4 w-4" />
            )}
          </Button>
        </div>
      </div>

      {isExpanded && (
        <div className="space-y-4 rounded-lg border bg-card p-3.5 shadow-sm transition-all dark:border-zinc-800">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-muted-foreground">
              Filter Options
            </span>
            <div className="flex items-center gap-2">
              <Button
                variant="ghost"
                size="icon"
                className="h-7 w-7 text-zinc-500 hover:text-zinc-900 dark:hover:text-zinc-100"
                title="Reset file size filter"
                onClick={handleReset}
              >
                <RotateCcw className="h-3.5 w-3.5" />
              </Button>

              <div className="flex items-center rounded-md border bg-muted p-0.5 text-xs">
                {(["KB", "MB", "GB"] as SizeUnit[]).map((unit) => (
                  <button
                    key={unit}
                    type="button"
                    className={cn(
                      "rounded px-2 py-0.5 text-xs font-medium transition-all",
                      localUnit === unit
                        ? "bg-background text-foreground shadow-sm"
                        : "text-muted-foreground hover:text-foreground",
                    )}
                    onClick={() => handleUnitChange(unit)}
                  >
                    {unit}
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className="flex flex-wrap gap-1.5 pt-0.5">
            {PRESETS.map((p) => {
              const active = isPresetActive(p);
              return (
                <Button
                  key={p.label}
                  type="button"
                  variant={active ? "default" : "outline"}
                  size="sm"
                  className={cn(
                    "h-7 px-2.5 text-xs font-normal transition-all",
                    active && "font-medium shadow-xs",
                    p.isPremium &&
                      !active &&
                      "border-amber-500/40 text-amber-600 hover:bg-amber-500/10 dark:text-amber-400",
                  )}
                  onClick={() => handleApplyPreset(p)}
                >
                  {p.isPremium && (
                    <Sparkles className="mr-1 h-3 w-3 text-amber-500" />
                  )}
                  {p.label}
                </Button>
              );
            })}
          </div>

          <div className="flex items-center gap-2 text-xs">
            <div className="flex-1 space-y-1">
              <span className="text-[11px] text-muted-foreground">Min ({localUnit})</span>
              <Input
                type="number"
                min={0}
                max={config.max}
                step={config.step}
                value={minInput}
                onChange={(e) => handleMinInputChange(e.target.value)}
                onBlur={handleInputBlur}
                className="h-8 text-xs"
              />
            </div>
            <span className="mt-4 text-muted-foreground">-</span>
            <div className="flex-1 space-y-1">
              <span className="text-[11px] text-muted-foreground">Max ({localUnit})</span>
              <Input
                type="number"
                min={0}
                max={config.max}
                step={config.step}
                value={maxInput}
                onChange={(e) => handleMaxInputChange(e.target.value)}
                onBlur={handleInputBlur}
                className="h-8 text-xs"
              />
            </div>
          </div>

          <div
            className="px-1 pt-1"
            onPointerDown={(e) => {
              e.stopPropagation();
            }}
          >
            <RangeSlider
              value={localRange}
              min={0}
              max={config.max}
              step={config.step}
              minStepsBetweenThumbs={config.step === 0.1 ? 0.1 : 1}
              className="w-full cursor-pointer"
              onValueChange={handleSliderChange}
            />
          </div>

          <div className="flex items-center justify-between text-[11px] text-muted-foreground pt-0.5">
            <div className="flex items-center gap-1.5">
              <span>
                Range: {localRange[0]} - {localRange[1]} {localUnit}
              </span>
            </div>
            <div className="flex items-center gap-1.5">
              <Badge
                variant="outline"
                className="cursor-pointer border-blue-500/30 text-[10px] text-blue-600 hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-950/40"
                onClick={() => {
                  if (localUnit === "GB") handleSliderChange([localRange[0], 2]);
                  else if (localUnit === "MB")
                    handleSliderChange([localRange[0], 2000]);
                  else handleUnitChange("GB");
                }}
              >
                Std 2GB
              </Badge>
              <Badge
                variant="outline"
                className="cursor-pointer border-amber-500/30 text-[10px] text-amber-600 hover:bg-amber-50 dark:text-amber-400 dark:hover:bg-amber-950/40"
                onClick={() => {
                  if (localUnit === "GB") handleSliderChange([localRange[0], 4]);
                  else if (localUnit === "MB")
                    handleSliderChange([localRange[0], 4000]);
                  else handleUnitChange("GB");
                }}
              >
                Premium 4GB
              </Badge>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

interface SortFilterProps {
  sort: SortFields | undefined;
  order: "asc" | "desc" | undefined;
  onChange: (sort: SortFields, order: "asc" | "desc") => void;
}

const SortFilter = ({ sort, order, onChange }: SortFilterProps) => {
  const currentSort = sort ?? "date";
  const currentOrder = order ?? "desc";

  const sortOptions = [
    { value: "date", label: "Sent Date" },
    { value: "completion_date", label: "Downloaded Date" },
    { value: "size", label: "File Size" },
    { value: "reaction_count", label: "Reaction Count" },
  ] as const;

  return (
    <div className="space-y-2">
      <Label>Sort By</Label>
      <div className="flex gap-2">
        <Select
          value={currentSort}
          onValueChange={(newSort: typeof currentSort) =>
            onChange(newSort, currentOrder)
          }
        >
          <SelectTrigger className="flex-1">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {sortOptions.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button
          variant="outline"
          size="icon"
          onClick={() =>
            onChange(currentSort, currentOrder === "asc" ? "desc" : "asc")
          }
          className={cn("h-9 w-9")}
        >
          {currentOrder === "asc" ? (
            <ArrowUpNarrowWide className="h-4 w-4" />
          ) : (
            <ArrowDownNarrowWide className="h-4 w-4" />
          )}
        </Button>
      </div>
    </div>
  );
};

interface FileFiltersProps {
  telegramId: string;
  chatId: string;
  filters: FileFilter;
  onFiltersChange: (filters: FileFilter) => void;
  clearFilters: () => void;
  showMobileLayoutToggle?: boolean;
}

export default function FileFilters({
  telegramId,
  chatId,
  filters,
  onFiltersChange,
  clearFilters,
  showMobileLayoutToggle = false,
}: FileFiltersProps) {
  const noAccountSpecified = telegramId === "-1" && chatId === "-1";
  const [localFilters, setLocalFilters] = useState<FileFilter>(filters);
  const isMobile = useIsMobile();
  const [open, setOpen] = useState(false);
  const [layout, setLayout] = useLocalStorage<"detailed" | "gallery">(
    "telegramFileLayout",
    "detailed",
  );

  useEffect(() => {
    setLocalFilters(filters);
  }, [filters]);

  const filterCount = Object.entries(filters).filter(([key, value]) => {
    if (["offline", "sort", "order", "dateType", "sizeUnit"].includes(key))
      return false;
    if (typeof value === "string") return value !== "";
    if (typeof value === "boolean") return value;
    if (Array.isArray(value)) return value.length > 0;
    return false;
  }).length;

  const handleSearchChange = (search: string) => {
    setLocalFilters((prev) => ({ ...prev, search }));
  };

  const handleTypeChange = (type: FileType | "all") => {
    setLocalFilters((prev) => ({ ...prev, type }));
  };

  const handleStatusChange = (
    downloadStatus?: DownloadStatus,
    transferStatus?: TransferStatus,
  ) => {
    setLocalFilters((prev) => ({
      ...prev,
      downloadStatus,
      transferStatus,
    }));
  };

  const handleTagsChange = (tags: string[]) => {
    setLocalFilters((prev) => ({ ...prev, tags }));
  };

  const handleDateChange = (
    dateType: "sent" | "downloaded",
    dateRange: [string, string],
  ) => {
    setLocalFilters((prev) => ({ ...prev, dateType, dateRange }));
  };

  const handleSizeChange = (
    sizeRange: [number, number],
    sizeUnit: "KB" | "MB" | "GB",
  ) => {
    setLocalFilters((prev) => ({ ...prev, sizeRange, sizeUnit }));
  };

  const handleSortChange = (sort: SortFields, order: "asc" | "desc") => {
    setLocalFilters((prev) => ({ ...prev, sort, order }));
  };

  const handleApply = () => {
    onFiltersChange(localFilters);
    setOpen(false);
  };

  const handleClear = () => {
    clearFilters();
    setOpen(false);
  };

  const [btnPos, setBtnPos] = useLocalStorage<{ x: number; y: number } | null>(
    "mobile_filter_btn_pos",
    null,
  );
  const [isDraggingBtn, setIsDraggingBtn] = useState(false);
  const dragRef = useRef<{
    startX: number;
    startY: number;
    initialX: number;
    initialY: number;
    hasMoved: boolean;
    timer: ReturnType<typeof setTimeout> | null;
  }>({
    startX: 0,
    startY: 0,
    initialX: 0,
    initialY: 0,
    hasMoved: false,
    timer: null,
  });

  const handleTouchStart = (e: React.TouchEvent) => {
    if (!isMobile) return;
    const touch = e.touches[0];
    if (!touch) return;

    const rect = e.currentTarget.getBoundingClientRect();
    const currentX = btnPos ? btnPos.x : rect.left;
    const currentY = btnPos ? btnPos.y : rect.top;

    dragRef.current = {
      startX: touch.clientX,
      startY: touch.clientY,
      initialX: currentX,
      initialY: currentY,
      hasMoved: false,
      timer: setTimeout(() => {
        setIsDraggingBtn(true);
      }, 200),
    };
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    if (!isMobile) return;
    const touch = e.touches[0];
    if (!touch) return;

    const dx = touch.clientX - dragRef.current.startX;
    const dy = touch.clientY - dragRef.current.startY;
    const dist = Math.hypot(dx, dy);

    if (dist > 10) {
      dragRef.current.hasMoved = true;
    }

    if (isDraggingBtn) {
      const btnSize = 44;
      const pad = 12;
      const maxX = window.innerWidth - btnSize - pad;
      const maxY = window.innerHeight - btnSize - pad;

      const newX = Math.min(Math.max(pad, dragRef.current.initialX + dx), maxX);
      const newY = Math.min(Math.max(pad, dragRef.current.initialY + dy), maxY);

      setBtnPos({ x: newX, y: newY });
    }
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    if (dragRef.current.timer) {
      clearTimeout(dragRef.current.timer);
    }
    if (dragRef.current.hasMoved || isDraggingBtn) {
      if (e.cancelable) {
        e.preventDefault();
      }
      e.stopPropagation();

      const preventGhostClick = (evt: MouseEvent) => {
        evt.preventDefault();
        evt.stopPropagation();
      };
      window.addEventListener("click", preventGhostClick, {
        capture: true,
        once: true,
      });
      setTimeout(() => {
        window.removeEventListener("click", preventGhostClick, { capture: true });
      }, 350);
    }
    setTimeout(() => {
      setIsDraggingBtn(false);
      dragRef.current.hasMoved = false;
    }, 300);
  };

  const handleTriggerClick = (e: React.MouseEvent) => {
    if (dragRef.current.hasMoved || isDraggingBtn) {
      e.preventDefault();
      e.stopPropagation();
    }
  };

  return (
    <Drawer
      open={open}
      onOpenChange={setOpen}
      swipeDirection={isMobile ? "down" : "left"}
      modal
    >
      <DrawerTrigger
        render={
          <Button
            variant="outline"
            className={cn(
              "relative gap-2 touch-none select-none",
              isMobile
                ? cn(
                    "fixed z-40 size-11 rounded-full shadow-lg transition-transform active:scale-95",
                    isDraggingBtn && "scale-110 shadow-2xl ring-4 ring-primary/40",
                    !btnPos &&
                      "bottom-[max(1rem,env(safe-area-inset-bottom))] right-4",
                  )
                : "",
            )}
            style={
              isMobile && btnPos
                ? {
                    left: `${btnPos.x}px`,
                    top: `${btnPos.y}px`,
                    position: "fixed",
                  }
                : undefined
            }
            onTouchStart={handleTouchStart}
            onTouchMove={handleTouchMove}
            onTouchEnd={handleTouchEnd}
            onClick={handleTriggerClick}
            aria-label={isMobile ? "Open filters" : undefined}
          >
            <Filter />
            {!isMobile && "Filters"}
            {filterCount > 0 && (
              <span className="absolute left-0 top-0 -ml-1 -mt-1 flex size-6 items-center justify-center rounded-full bg-red-500 text-xs text-white">
                {filterCount}
              </span>
            )}
          </Button>
        }
      />
      <DrawerContent
        className={cn(
          isMobile
            ? "h-[min(92dvh,calc(100dvh-1rem))] max-h-[calc(100dvh-1rem)]"
            : "w-[380px]",
        )}
      >
        <div className="no-scrollbar min-h-0 flex-1 touch-pan-y overflow-y-auto overscroll-contain p-6">
          <DrawerTitle>
            <div className="flex items-center justify-between">
              <span className="text-xl font-semibold text-zinc-900 dark:text-zinc-100">
                Filters
              </span>
              {!noAccountSpecified && (
                <div className="flex items-center space-x-2">
                  <Label
                    htmlFor="offline"
                    className="cursor-pointer text-zinc-500"
                  >
                    Offline
                  </Label>
                  <Switch
                    id="offline"
                    checked={localFilters.offline}
                    onCheckedChange={(checked) => {
                      setLocalFilters((prev) => ({
                        ...prev,
                        offline: checked,
                      }));
                    }}
                  />
                </div>
              )}
              {noAccountSpecified && (
                <div className="flex items-center space-x-2">
                  <Label
                    htmlFor="seedOnly"
                    className="cursor-pointer text-zinc-500"
                  >
                    Seed only
                  </Label>
                  <Switch
                    id="seedOnly"
                    checked={localFilters.seedOnly}
                    onCheckedChange={(checked) => {
                      setLocalFilters((prev) => ({
                        ...prev,
                        seedOnly: checked,
                        type: checked ? "all" : prev.type,
                      }));
                    }}
                  />
                </div>
              )}
            </div>
          </DrawerTitle>
          <DrawerDescription className="mb-3">
            Default search by Telegram Client, you can choose offline to
            search by local database.
          </DrawerDescription>

          <div className="space-y-4 p-0.5">
            {isMobile && showMobileLayoutToggle && (
              <div className="rounded-xl border bg-card p-3 shadow-sm">
                <div className="mb-2 flex items-center justify-between gap-3">
                  <div>
                    <Label className="text-sm font-semibold">Layout</Label>
                    <p className="text-xs text-muted-foreground">
                      Switch how files are shown in the list.
                    </p>
                  </div>
                  <Toggle
                    className="h-10 shrink-0 gap-2 border px-3"
                    pressed={layout === "gallery"}
                    onPressedChange={(pressed) => {
                      setLayout(pressed ? "gallery" : "detailed");
                    }}
                    aria-label="Toggle file list layout"
                  >
                    {layout === "detailed" ? (
                      <>
                        <List className="h-4 w-4" />
                        <span className="text-xs font-medium">Detailed</span>
                      </>
                    ) : (
                      <>
                        <GalleryHorizontal className="h-4 w-4" />
                        <span className="text-xs font-medium">Gallery</span>
                      </>
                    )}
                  </Toggle>
                </div>
              </div>
            )}

            <SearchFilter
              search={localFilters.search}
              onChange={handleSearchChange}
            />

            <FileTypeFilter
              offline={localFilters.offline}
              telegramId={telegramId}
              chatId={chatId}
              type={filters.type}
              seedOnly={localFilters.seedOnly}
              onChange={handleTypeChange}
            />

            {!localFilters.offline && (
              <div className="flex items-center justify-between rounded-md border bg-gray-100/50 px-2 py-3 dark:bg-gray-600/50">
                <Label htmlFor="notDownload">Filter Not Download</Label>
                <Switch
                  id="notDownload"
                  checked={localFilters.downloadStatus === "idle"}
                  onCheckedChange={(checked) => {
                    setLocalFilters((prev) => ({
                      ...prev,
                      downloadStatus: checked ? "idle" : undefined,
                    }));
                  }}
                  aria-label="Not Download"
                />
              </div>
            )}

            {localFilters.offline && (
              <>
                <FileStatusFilter
                  downloadStatus={localFilters.downloadStatus}
                  transferStatus={localFilters.transferStatus}
                  onChange={handleStatusChange}
                />

                <TagsFilter
                  tags={localFilters.tags}
                  onChange={handleTagsChange}
                />

                <DateFilter
                  dateType={localFilters.dateType}
                  dateRange={localFilters.dateRange}
                  onChange={handleDateChange}
                />

                <SizeFilter
                  sizeRange={localFilters.sizeRange}
                  sizeUnit={localFilters.sizeUnit}
                  onChange={handleSizeChange}
                />

                <SortFilter
                  sort={localFilters.sort}
                  order={localFilters.order}
                  onChange={handleSortChange}
                />
              </>
            )}
          </div>
        </div>

        <DrawerFooter className="shrink-0 border-t bg-background pb-[max(1rem,env(safe-area-inset-bottom))]">
          <Button onClick={handleApply}>Apply Filters</Button>
          <Button variant="outline" onClick={handleClear}>
            Clear Filters
          </Button>
        </DrawerFooter>
      </DrawerContent>
    </Drawer>
  );
}
