import React from "react";
import useSWR from "swr";
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  CheckCircle,
  Clock,
  CloudDownload,
  Download,
  File,
  FileText,
  Image,
  LineChart,
  Music,
  Network,
  PauseCircle,
  Upload,
  Video,
  type LucideIcon,
} from "lucide-react";
import { telegramApi, type TelegramApiArg } from "@/lib/api";
import { formatDistanceToNow } from "date-fns";
import { Button } from "@/components/ui/button";
import useSWRMutation from "swr/mutation";
import type { TelegramApiResult } from "@/lib/types"; // Define a fetcher function to handle the API request
import prettyBytes from "pretty-bytes";
import { useSettings } from "@/hooks/use-settings";
import { DotmTriangle2 } from "@/components/ui/dotm-triangle-2";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

// Interface defining the structure of the data returned from the API
interface StatisticsData {
  total: number;
  downloading: number;
  paused: number;
  completed: number;
  error: number;
  photo: number;
  video: number;
  audio: number;
  file: number;
  networkStatistics: {
    sinceDate: number;
    sentBytes: number;
    receivedBytes: number;
  };
  speedStats: {
    interval: number;
    avgSpeed: number;
    maxSpeed: number;
    medianSpeed: number;
    minSpeed: number;
  };
}

// Props interface for the component, expecting a telegramId as input
interface FileStatisticsProps {
  telegramId: string;
}

interface MetricCardProps {
  label: string;
  value: React.ReactNode;
  icon: LucideIcon;
  emphasis?: "default" | "muted" | "destructive";
}

function MetricCard({
  label,
  value,
  icon: Icon,
  emphasis = "default",
}: MetricCardProps) {
  return (
    <div className="flex min-h-24 flex-col justify-between gap-3 rounded-md border bg-card p-4 shadow-sm transition-colors hover:bg-accent/40">
      <div className="flex items-center justify-between gap-3">
        <span className="truncate text-sm font-medium text-muted-foreground">
          {label}
        </span>
        <span
          className={
            emphasis === "destructive"
              ? "text-destructive"
              : emphasis === "muted"
                ? "text-muted-foreground"
                : "text-primary"
          }
        >
          <Icon className="size-4" />
        </span>
      </div>
      <div className="truncate text-2xl font-semibold tracking-normal text-foreground">
        {value}
      </div>
    </div>
  );
}

function SectionTitle({
  icon: Icon,
  title,
  description,
}: {
  icon: LucideIcon;
  title: string;
  description?: React.ReactNode;
}) {
  return (
    <CardHeader className="flex flex-row items-start justify-between gap-3 p-4 pb-3">
      <div className="flex min-w-0 items-center gap-2">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-md border bg-muted text-muted-foreground">
          <Icon className="size-4" />
        </span>
        <div className="min-w-0">
          <CardTitle className="truncate text-base">{title}</CardTitle>
          {description && (
            <p className="mt-0.5 text-sm text-muted-foreground">
              {description}
            </p>
          )}
        </div>
      </div>
    </CardHeader>
  );
}

const FileStatistics: React.FC<FileStatisticsProps> = ({ telegramId }) => {
  const { settings } = useSettings();
  // Use SWR for data fetching and caching
  const { data, error, mutate } = useSWR<StatisticsData, Error>(
    `/telegram/${telegramId}/download-statistics`,
  );

  const { trigger: triggerReset, isMutating: isResetMutating } = useSWRMutation<
    TelegramApiResult,
    Error,
    string,
    TelegramApiArg
  >("/telegram/api", telegramApi, {
    onSuccess: () => {
      void mutate();
    },
  });

  // Render an error message if the API call fails
  if (error) {
    return (
      <Card className="border-destructive/40">
        <CardContent className="flex items-center gap-3 p-4 text-destructive">
          <AlertTriangle className="size-5" />
          <span className="text-sm font-medium">
            Failed to load statistics.
          </span>
        </CardContent>
      </Card>
    );
  }

  // Render a loading indicator while the data is being fetched
  if (!data) {
    return (
      <Card>
        <CardContent className="flex items-center gap-3 p-4 text-muted-foreground">
          <DotmTriangle2
            size={20}
            dotSize={2}
            speed={1.4}
            opacityBase={0.1}
            opacityMid={0.4}
            opacityPeak={0.95}
            ariaLabel="Loading statistics"
          />
          <span className="text-sm font-medium">Loading statistics...</span>
        </CardContent>
      </Card>
    );
  }

  // Destructure the fetched data for easier usage
  const {
    total,
    downloading,
    paused,
    completed,
    error: errorCount,
    photo,
    video,
    audio,
    file,
  } = data;

  // Prepare an array of completed file types with their respective icons
  const completedTypes = [
    {
      label: "Photo",
      value: photo,
      icon: Image,
    },
    {
      label: "Video",
      value: video,
      icon: Video,
    },
    {
      label: "Audio",
      value: audio,
      icon: Music,
    },
    {
      label: "File",
      value: file,
      icon: File,
    },
  ];

  const avgStatFields = [
    {
      label: "Average",
      value:
        prettyBytes(data.speedStats.avgSpeed, {
          bits: settings?.speedUnits === "bits",
        }) + "/s",
      icon: PauseCircle,
    },
    {
      label: "Maximum",
      value:
        prettyBytes(data.speedStats.maxSpeed, {
          bits: settings?.speedUnits === "bits",
        }) + "/s",
      icon: ArrowUp,
    },
    {
      label: "Median",
      value:
        prettyBytes(data.speedStats.medianSpeed, {
          bits: settings?.speedUnits === "bits",
        }) + "/s",
      icon: LineChart,
    },
    {
      label: "Minimum",
      value:
        prettyBytes(data.speedStats.minSpeed, {
          bits: settings?.speedUnits === "bits",
        }) + "/s",
      icon: ArrowDown,
    },
  ];
  const intervalMinutes = Math.max(1, data.speedStats.interval / 60);
  const statusMetrics: MetricCardProps[] = [
    {
      label: "Total files",
      value: total,
      icon: FileText,
      emphasis: "muted",
    },
    {
      label: "Downloading",
      value: downloading,
      icon: Download,
    },
    {
      label: "Paused",
      value: paused,
      icon: PauseCircle,
      emphasis: "muted",
    },
    {
      label: "Completed",
      value: completed,
      icon: CheckCircle,
    },
    {
      label: "Errors",
      value: errorCount,
      icon: AlertTriangle,
      emphasis: errorCount > 0 ? "destructive" : "muted",
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <SectionTitle
          icon={CloudDownload}
          title="Download Statistics"
          description="Current file inventory and transfer state"
        />
        <CardContent className="grid grid-cols-1 gap-3 p-4 pt-0 sm:grid-cols-2 xl:grid-cols-5">
          {statusMetrics.map((metric) => (
            <MetricCard key={metric.label} {...metric} />
          ))}
        </CardContent>
      </Card>

      <Card>
        <SectionTitle
          icon={Clock}
          title="Speed Statistics"
          description={`${intervalMinutes} minute interval`}
        />
        <CardContent className="grid grid-cols-1 gap-3 p-4 pt-0 sm:grid-cols-2 xl:grid-cols-4">
          {avgStatFields.map((stat, index) => (
            <MetricCard
              key={`${stat.label}-${index}`}
              label={stat.label}
              value={stat.value}
              icon={stat.icon}
              emphasis={stat.label === "Minimum" ? "muted" : "default"}
            />
          ))}
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <SectionTitle
            icon={CheckCircle}
            title="Completed by Type"
            description="Downloaded files grouped by media class"
          />
          <CardContent className="p-4 pt-0">
            <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              {completedTypes.map((type) => (
                <li
                  key={type.label}
                  className="flex items-center justify-between gap-3 rounded-md border bg-card p-4 shadow-sm"
                >
                  <div className="flex min-w-0 items-center gap-3">
                    <span className="flex size-9 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground">
                      <type.icon className="size-4" />
                    </span>
                    <span className="truncate text-sm font-medium text-muted-foreground">
                      {type.label}
                    </span>
                  </div>
                  <div className="text-2xl font-semibold text-foreground">
                    {type.value}
                  </div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>

        <Card>
          <SectionTitle
            icon={Network}
            title="Network Statistics"
            description={
              <>
                Since{" "}
                {formatDistanceToNow(
                  new Date(data.networkStatistics.sinceDate * 1000),
                  {
                    addSuffix: true,
                  },
                )}
              </>
            }
          />
          <CardContent className="flex flex-col gap-3 p-4 pt-0">
            <div className="rounded-md border bg-card p-4 shadow-sm">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <span className="flex size-9 items-center justify-center rounded-md bg-muted text-muted-foreground">
                    <Upload className="size-4" />
                  </span>
                  <span className="text-sm font-medium text-muted-foreground">
                    Sent
                  </span>
                </div>
                <Badge variant="secondary" className="font-mono">
                  {prettyBytes(data.networkStatistics.sentBytes)}
                </Badge>
              </div>
            </div>
            <div className="rounded-md border bg-card p-4 shadow-sm">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <span className="flex size-9 items-center justify-center rounded-md bg-muted text-muted-foreground">
                    <Download className="size-4" />
                  </span>
                  <span className="text-sm font-medium text-muted-foreground">
                    Received
                  </span>
                </div>
                <Badge variant="secondary" className="font-mono">
                  {prettyBytes(data.networkStatistics.receivedBytes)}
                </Badge>
              </div>
            </div>
            <div className="flex items-center justify-between gap-3 pt-1">
              <p className="text-sm text-muted-foreground">
                Traffic counters can be reset without changing file history.
              </p>
              <Button
                variant="outline"
                size="sm"
                disabled={isResetMutating}
                onClick={() => {
                  void triggerReset({
                    data: {},
                    method: "ResetNetworkStatistics",
                  });
                }}
              >
                {isResetMutating ? "Resetting..." : "Reset"}
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
};

export default FileStatistics;
