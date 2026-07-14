import {
  Bell,
  Copy,
  DownloadCloud,
  EyeOff,
  FolderOpen,
  Gauge,
  LogOut,
  Shield,
  Tags,
  type LucideIcon,
} from "lucide-react";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import React, { type FormEvent, useState } from "react";
import { useSettings } from "@/hooks/use-settings";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import { useCopyToClipboard } from "@/hooks/use-copy-to-clipboard";
import { DialogClose, DialogFooter } from "@/components/ui/dialog";
import TimeRangeSelector from "@/components/ui/time-range-selector";
import { Switch } from "@/components/ui/switch";
import { type SettingKey } from "@/lib/types";
import { Slider } from "@/components/ui/slider";
import { TagsInput } from "@/components/ui/tags-input";
import { split } from "lodash";
import { RadioGroup, RadioGroupItem } from "./ui/radio-group";
import { useAdminSession } from "@/hooks/use-admin-session";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

interface SettingsSectionProps {
  icon: LucideIcon;
  title: string;
  description?: string;
  children: React.ReactNode;
}

function SettingsSection({
  icon: Icon,
  title,
  description,
  children,
}: SettingsSectionProps) {
  return (
    <Card>
      <CardHeader className="p-4 pb-3">
        <div className="flex items-start gap-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-md border bg-muted text-muted-foreground">
            <Icon className="size-4" />
          </span>
          <div className="min-w-0">
            <CardTitle className="text-base">{title}</CardTitle>
            {description && (
              <p className="mt-0.5 text-sm text-muted-foreground">
                {description}
              </p>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-4 p-4 pt-0">
        {children}
      </CardContent>
    </Card>
  );
}

interface SettingRowProps {
  label: string;
  description?: React.ReactNode;
  children: React.ReactNode;
  onClick?: (event: React.MouseEvent<HTMLDivElement>) => void;
}

function SettingRow({
  label,
  description,
  children,
  onClick,
}: SettingRowProps) {
  return (
    <div
      className="flex flex-col gap-3 rounded-md border bg-card p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between"
      onClick={onClick}
    >
      <div className="min-w-0">
        <Label className={onClick ? "cursor-pointer" : undefined}>
          {label}
        </Label>
        {description && (
          <p className="mt-1 text-sm leading-5 text-muted-foreground">
            {description}
          </p>
        )}
      </div>
      <div className="shrink-0">{children}</div>
    </div>
  );
}

export default function SettingsForm() {
  const { settings, setSetting, updateSettings } = useSettings();
  const { account } = useTelegramAccount();
  const { session, logout } = useAdminSession();
  const [, copyToClipboard] = useCopyToClipboard();
  const [loggingOut, setLoggingOut] = useState(false);

  const avgSpeedIntervalOptions = [
    { value: "60", label: "1 minute" },
    { value: "300", label: "5 minutes" },
    { value: "600", label: "10 minutes" },
    { value: "900", label: "15 minutes" },
    { value: "1800", label: "30 minutes" },
  ];

  const handleSave = async (e: FormEvent) => {
    e.preventDefault();
    await updateSettings();
  };

  const handleSwitchChange = (
    key: SettingKey,
    event?: React.MouseEvent<HTMLDivElement>,
  ) => {
    if (event && event.target instanceof HTMLInputElement) return;
    event?.stopPropagation();
    void setSetting(key, String(!(settings?.[key] === "true")));
  };

  const handleLogout = async () => {
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setLoggingOut(false);
    }
  };

  return (
    <form
      onSubmit={handleSave}
      className="flex h-full flex-col overflow-hidden"
    >
      <div className="flex flex-1 flex-col gap-4 overflow-y-auto pr-1">
        <div className="flex items-start gap-3 rounded-md border bg-muted/40 p-3 text-sm text-muted-foreground">
          <Bell className="mt-0.5 size-4 shrink-0" />
          <p>These settings are shared across every Telegram account.</p>
        </div>

        <SettingsSection
          icon={FolderOpen}
          title="Storage"
          description="Current account storage location"
        >
          <div className="flex flex-col gap-2 rounded-md border bg-card p-4 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <Label>Your root path</Label>
              <Badge variant="secondary">Read only</Badge>
            </div>
            <div className="flex items-center gap-2">
              <p className="min-w-0 flex-1 truncate rounded-md bg-muted px-3 py-2 font-mono text-xs text-muted-foreground">
                {account?.rootPath || "No account selected"}
              </p>
              <Button
                variant="ghost"
                size="icon"
                aria-label="Copy root path"
                disabled={!account?.rootPath}
                onClick={(e) => {
                  e.preventDefault();
                  void copyToClipboard(account?.rootPath ?? "");
                }}
              >
                <Copy />
              </Button>
            </div>
          </div>
        </SettingsSection>

        <SettingsSection
          icon={Gauge}
          title="Display"
          description="Units and table visibility preferences"
        >
          <SettingRow
            label="Speed units"
            description="Choose how transfer speed is displayed throughout the app."
          >
            <RadioGroup
              value={settings?.speedUnits || "bits"}
              onValueChange={(v) => void setSetting("speedUnits", v)}
              className="group inline-flex h-9 items-center justify-center rounded-lg bg-muted p-1 text-muted-foreground"
              data-state={settings?.speedUnits || "bits"}
            >
              <label className="inline-flex cursor-pointer items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-sm font-medium ring-offset-background transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 group-data-[state=bits]:bg-background group-data-[state=bits]:text-foreground group-data-[state=bits]:shadow">
                bits
                <RadioGroupItem
                  id="enspeedUnits-bits"
                  value="bits"
                  className="sr-only"
                />
              </label>
              <label className="inline-flex cursor-pointer items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-sm font-medium ring-offset-background transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50 group-data-[state=bytes]:bg-background group-data-[state=bytes]:text-foreground group-data-[state=bytes]:shadow">
                bytes
                <RadioGroupItem
                  id="speedUnits-bytes"
                  value="bytes"
                  className="sr-only"
                />
              </label>
            </RadioGroup>
          </SettingRow>

          <SettingRow
            label="Unique only"
            description={
              <>
                Show only unique files in the table. When enabled, document
                counts may be less precise.
              </>
            }
            onClick={(event) => handleSwitchChange("uniqueOnly", event)}
          >
            <Switch
              id="unique-only"
              checked={settings?.uniqueOnly === "true"}
              onCheckedChange={() => handleSwitchChange("uniqueOnly")}
            />
          </SettingRow>
        </SettingsSection>

        <SettingsSection
          icon={EyeOff}
          title="Privacy"
          description="Control how sensitive file content appears in lists"
        >
          <SettingRow
            label="Always hide"
            description="Always hide content and extra information in the table."
            onClick={(event) => handleSwitchChange("alwaysHide", event)}
          >
            <Switch
              id="always-hide"
              checked={settings?.alwaysHide === "true"}
              onCheckedChange={() => handleSwitchChange("alwaysHide")}
            />
          </SettingRow>

          {settings?.alwaysHide === "false" && (
            <SettingRow
              label="Show sensitive content"
              description="When disabled, sensitive content is still present but hidden behind a spoiler."
              onClick={(event) =>
                handleSwitchChange("showSensitiveContent", event)
              }
            >
              <Switch
                id="show-sensitive-content"
                checked={settings?.showSensitiveContent === "true"}
                onCheckedChange={() =>
                  handleSwitchChange("showSensitiveContent")
                }
              />
            </SettingRow>
          )}
        </SettingsSection>

        <SettingsSection
          icon={DownloadCloud}
          title="Auto Download"
          description="Limits and timing for automatic downloads"
        >
          <SettingRow
            label="Auto load thumbnails"
            description="Download lightweight preview thumbnails while browsing files."
            onClick={(event) => handleSwitchChange("thumbnailAutoLoad", event)}
          >
            <Switch
              id="thumbnail-auto-load"
              checked={settings?.thumbnailAutoLoad === "true"}
              onCheckedChange={() => handleSwitchChange("thumbnailAutoLoad")}
            />
          </SettingRow>

          <div className="rounded-md border bg-card p-4 shadow-sm">
            <div className="flex items-center justify-between gap-4">
              <div>
                <Label htmlFor="limit">Limit per account</Label>
                <p className="mt-1 text-sm text-muted-foreground">
                  Maximum concurrent automatic downloads per account.
                </p>
              </div>
              <span className="text-muted-foreground">
                {settings?.autoDownloadLimit ?? 5} / 10
              </span>
            </div>
            <Slider
              value={[Number(settings?.autoDownloadLimit ?? 5)]}
              onValueChange={(v) => {
                void setSetting("autoDownloadLimit", String(v[0]));
              }}
              min={1}
              max={10}
              step={1}
              className="mt-4 w-full"
            />
          </div>

          <div className="grid gap-4 rounded-md border bg-card p-4 shadow-sm md:grid-cols-[1fr_220px] md:items-center">
            <div>
              <Label htmlFor="avg-speed-interval">Average speed interval</Label>
              <p className="mt-1 text-sm text-muted-foreground">
                Longer intervals smooth the chart, but may consume more memory.
              </p>
            </div>
            <Select
              value={String(settings?.avgSpeedInterval)}
              onValueChange={(v) => void setSetting("avgSpeedInterval", v)}
            >
              <SelectTrigger id="avg-speed-interval">
                <SelectValue placeholder="Select Avg Speed Interval" />
              </SelectTrigger>
              <SelectContent>
                {avgSpeedIntervalOptions.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-3 rounded-md border bg-card p-4 shadow-sm">
            <Label htmlFor="time-limited">Time Limited</Label>
            <TimeRangeSelector
              startRequired={true}
              endRequired={true}
              includeSeconds={false}
              timeRange={
                settings?.autoDownloadTimeLimited
                  ? JSON.parse(settings.autoDownloadTimeLimited)
                  : { startTime: "00:00", endTime: "00:00" }
              }
              onTimeRangeChange={(
                startTime: string | null,
                endTime: string | null,
              ) => {
                void setSetting(
                  "autoDownloadTimeLimited",
                  JSON.stringify({
                    startTime: startTime ?? "00:00",
                    endTime: endTime ?? "00:00",
                  }),
                );
              }}
              className="max-w-md"
            />
            <p className="text-sm text-muted-foreground">
              Set both values to 00:00 to disable the time window.
            </p>
          </div>
        </SettingsSection>

        <SettingsSection
          icon={Tags}
          title="Tags"
          description="Reusable labels available when organizing files"
        >
          <div className="rounded-md border bg-card p-4 shadow-sm">
            <TagsInput
              maxTags={20}
              value={
                (settings?.tags?.length ?? 0) > 0
                  ? split(settings?.tags, ",")
                  : []
              }
              onChange={(tags) => void setSetting("tags", tags.join(","))}
            />
          </div>
        </SettingsSection>

        <SettingsSection
          icon={Shield}
          title="Administrator Session"
          description="End the current administrator session on this device"
        >
          <div className="flex items-center justify-between gap-4 rounded-md border bg-card p-4 shadow-sm">
            <div className="flex min-w-0 flex-col gap-1">
              <Label>Signed in account</Label>
              <p className="truncate text-xs text-muted-foreground">
                Signed in as {session?.username ?? "administrator"}
              </p>
            </div>
            <Button
              type="button"
              variant="destructive"
              disabled={loggingOut}
              onClick={() => void handleLogout()}
            >
              <LogOut data-icon="inline-start" />
              {loggingOut ? "Logging out…" : "Log out"}
            </Button>
          </div>
        </SettingsSection>
      </div>
      <DialogFooter className="mt-3 border-t bg-background pt-3 gap-2">
        <DialogClose asChild>
          <Button className="w-full md:w-auto" variant="outline" type="button">
            Cancel
          </Button>
        </DialogClose>
        <Button className="w-full md:w-auto" type="submit">
          Save settings
        </Button>
      </DialogFooter>
    </form>
  );
}
