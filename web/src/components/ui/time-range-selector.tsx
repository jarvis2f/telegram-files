import { useI18n } from "@/hooks/use-i18n";
import { useEffect, useState } from "react";
import { ArrowRight, Clock } from "lucide-react";
import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "./popover";

interface TimeRangeSelectorProps {
  startRequired?: boolean;
  endRequired?: boolean;
  includeSeconds?: boolean;
  timeRange?: {
    startTime: string | null;
    endTime: string | null;
  };
  onTimeRangeChange?: (
    startTime: string | null,
    endTime: string | null,
  ) => void;
  className?: string;
}

type TimeUnit = {
  hours: string;
  minutes: string;
  seconds?: string;
};

const TimeRangeSelector = ({
  startRequired = false,
  endRequired = false,
  includeSeconds = false,
  timeRange,
  onTimeRangeChange,
  className,
}: TimeRangeSelectorProps) => {
  const { t } = useI18n();
  // Time states
  const [startTime, setStartTime] = useState<TimeUnit | null>(
    startRequired ? parseTime(timeRange?.startTime) : null,
  );
  const [endTime, setEndTime] = useState<TimeUnit | null>(
    endRequired ? parseTime(timeRange?.endTime) : null,
  );

  // Active selection state
  const [activeSelection, setActiveSelection] = useState<
    "start" | "end" | null
  >(null);
  const [isPickerOpen, setIsPickerOpen] = useState(false);

  useEffect(() => {
    if (activeSelection && !isPickerOpen) {
      setActiveSelection(null);
    }
  }, [activeSelection, isPickerOpen]);

  // Format time to HH:mm:ss
  const formatTime = (time: TimeUnit | null): string | null => {
    if (!time) return null;

    return includeSeconds
      ? `${time.hours}:${time.minutes}:${time.seconds ?? "00"}`
      : `${time.hours}:${time.minutes}`;
  };

  function parseTime(time?: string | null): TimeUnit {
    if (!time) return { hours: "00", minutes: "00", seconds: "00" };
    const [hours, minutes, seconds] = time.split(":").map(Number);
    return {
      hours: String(hours).padStart(2, "0"),
      minutes: String(minutes).padStart(2, "0"),
      seconds: includeSeconds
        ? String(seconds ?? 0).padStart(2, "0")
        : undefined,
    };
  }

  // Generate time options for scrollable selection
  const generateOptions = (
    max: number,
    value: string,
    onChange: (value: string) => void,
  ) => {
    return (
      <div className="scrollbar-thin h-28 overflow-y-auto px-1">
        {Array.from({ length: max }, (_, i) => {
          const optionValue = i.toString().padStart(2, "0");
          return (
            <div
              key={optionValue}
              className={cn(
                "cursor-pointer rounded py-1 text-center text-sm hover:bg-accent hover:text-accent-foreground",
                optionValue === value && "bg-accent text-accent-foreground",
              )}
              onClick={() => onChange(optionValue)}
            >
              {optionValue}
            </div>
          );
        })}
      </div>
    );
  };

  // Handle time selection
  const handleTimeChange = (unit: keyof TimeUnit, value: string) => {
    if (activeSelection === "start") {
      const newStartTime = { ...startTime!, [unit]: value };
      setStartTime(newStartTime);
      onTimeRangeChange?.(formatTime(newStartTime), formatTime(endTime));
    } else if (activeSelection === "end") {
      const newEndTime = { ...endTime!, [unit]: value };
      setEndTime(newEndTime);
      onTimeRangeChange?.(formatTime(startTime), formatTime(newEndTime));
    }
  };

  // Handle click on start or end time
  const handleTimeClick = (type: "start" | "end") => {
    // Initialize time if not set
    if (type === "start" && !startTime) {
      setStartTime({
        hours: "00",
        minutes: "00",
        seconds: includeSeconds ? "00" : undefined,
      });
    } else if (type === "end" && !endTime) {
      setEndTime({
        hours: "00",
        minutes: "00",
        seconds: includeSeconds ? "00" : undefined,
      });
    }

    setActiveSelection(type);
    setIsPickerOpen(true);
  };

  return (
    <Popover open={isPickerOpen} onOpenChange={setIsPickerOpen}>
      <div className={className}>
        {/* Main time range display */}
        <PopoverTrigger asChild>
          <div className="flex h-10 items-center rounded-md border bg-background shadow-sm dark:border-input">
            {/* Clock icon */}
            <div className="pl-3 pr-1">
              <Clock className="h-4 w-4 text-muted-foreground" />
            </div>

            {/* Start time section */}
            <div
              className={cn(
                "flex-1 cursor-pointer rounded px-2 py-2 text-center text-sm",
                activeSelection === "start" && "bg-accent",
                !startTime && "text-muted-foreground",
              )}
              onClick={() => handleTimeClick("start")}
            >
              {startTime ? formatTime(startTime) : t("Start Time")}
            </div>

            {/* Separator */}
            <div className="px-1">
              <ArrowRight className="h-4 w-8 text-muted-foreground" />
            </div>

            {/* End time section */}
            <div
              className={cn(
                "flex-1 cursor-pointer rounded px-2 py-2 text-center text-sm",
                activeSelection === "end" && "bg-accent",
                !endTime && "text-muted-foreground",
              )}
              onClick={() => handleTimeClick("end")}
            >
              {endTime ? formatTime(endTime) : t("End Time")}
            </div>

            {/* Right padding */}
            <div className="pr-3"></div>
          </div>
        </PopoverTrigger>
        {/* Time picker dropdown */}
        <PopoverContent className="p-2" modal={true}>
          <div className="flex gap-2">
            {/* Hours column */}
            <div className="flex-1">
              <div className="mb-1 text-center text-xs text-muted-foreground">
                {t("Hour")}
              </div>
              {generateOptions(
                24,
                activeSelection === "start"
                  ? (startTime?.hours ?? "00")
                  : (endTime?.hours ?? "00"),
                (value) => handleTimeChange("hours", value),
              )}
            </div>

            {/* Minutes column */}
            <div className="flex-1">
              <div className="mb-1 text-center text-xs text-muted-foreground">
                {t("Min")}
              </div>
              {generateOptions(
                60,
                activeSelection === "start"
                  ? (startTime?.minutes ?? "00")
                  : (endTime?.minutes ?? "00"),
                (value) => handleTimeChange("minutes", value),
              )}
            </div>

            {/* Seconds column (optional) */}
            {includeSeconds && (
              <div className="flex-1">
                <div className="mb-1 text-center text-xs text-muted-foreground">
                  {t("Sec")}
                </div>
                {generateOptions(
                  60,
                  activeSelection === "start"
                    ? (startTime?.seconds ?? "00")
                    : (endTime?.seconds ?? "00"),
                  (value) => handleTimeChange("seconds", value),
                )}
              </div>
            )}
          </div>
        </PopoverContent>
      </div>
    </Popover>
  );
};

export default TimeRangeSelector;
