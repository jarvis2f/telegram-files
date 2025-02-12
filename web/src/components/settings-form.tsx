import {Bell, Copy} from "lucide-react";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import React, { type FormEvent } from "react";
import { useSettings } from "@/hooks/use-settings";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import {useCopyToClipboard} from "@/hooks/use-copy-to-clipboard";

export default function SettingsForm() {
  const { settings, setSetting, updateSettings } = useSettings();
  const { account } = useTelegramAccount();
  const [, copyToClipboard] = useCopyToClipboard();

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

  return (
    <form
      onSubmit={handleSave}
      className="flex h-full flex-col overflow-hidden"
    >
      <div className="flex flex-col space-y-4 overflow-y-scroll">
        <p className="rounded-md bg-gray-50 p-2 text-sm text-muted-foreground shadow dark:bg-gray-700">
          <Bell className="mr-2 inline-block h-4 w-4" />
          These settings will be applied to all accounts.
        </p>
        <div className="w-full rounded-md border p-4 shadow">
          <p className="mb-1 text-xs text-muted-foreground">Your root path</p>
          <div className="flex items-center justify-between space-x-1">
            <p className="bg-gray-50 rounded-md p-2 text-xs text-muted-foreground dark:bg-gray-700">
              {account?.rootPath}
            </p>
            <Button
              variant="ghost"
              size="sm"
              onClick={(e) => {
                e.preventDefault();
                void copyToClipboard(account?.rootPath ?? "");
              }}
            >
              <Copy className="h-4 w-4" />
            </Button>
          </div>
        </div>
        <div className="flex w-full flex-col space-y-4 rounded-md border p-4 shadow">
          <div className="flex items-center space-x-2">
            <Label htmlFor="unique-only">Unique Only</Label>
            <Checkbox
              id="unique-only"
              checked={settings?.uniqueOnly === "true"}
              onCheckedChange={(checked) =>
                void setSetting("uniqueOnly", String(checked))
              }
            />
          </div>
          <p className="text-xs text-muted-foreground">
            Show only unique file in the table. If disabled, will show all.{" "}
            <br />
            <strong>Warning:</strong> If enabled, the number of documents on the
            form will be inaccurate.
          </p>
        </div>
        <div className="flex w-full flex-col space-y-4 rounded-md border p-4 shadow">
          <div className="flex items-center space-x-2">
            <Label htmlFor="always-hide">Always Hide</Label>
            <Checkbox
              id="always-hide"
              checked={settings?.alwaysHide === "true"}
              onCheckedChange={(checked) =>
                void setSetting("alwaysHide", String(checked))
              }
            />
          </div>
          <p className="text-xs text-muted-foreground">
            Always hide content and extra info in the table.
          </p>
          {settings?.alwaysHide === "false" && (
            <>
              <div className="flex items-center space-x-2">
                <Label htmlFor="show-sensitive-content">
                  Show Sensitive Content
                </Label>
                <Checkbox
                  id="show-sensitive-content"
                  checked={settings?.showSensitiveContent === "true"}
                  onCheckedChange={(checked) =>
                    void setSetting("showSensitiveContent", String(checked))
                  }
                />
              </div>
              <p className="text-xs text-muted-foreground">
                Show sensitive content in the table, Will use a spoiler to hide
                sensitive content if disabled.
              </p>
            </>
          )}
        </div>
        <div className="flex w-full flex-col space-y-4 rounded-md border p-4 shadow">
          <Label>Auto Download Settings</Label>
          <div className="flex flex-col space-y-2">
            <Label htmlFor="limit">Limit Per Account</Label>
            <Input
              id="limit"
              className="w-24"
              type="number"
              min={1}
              max={10}
              value={settings?.autoDownloadLimit ?? 5}
              onChange={(e) => {
                void setSetting("autoDownloadLimit", e.target.value);
              }}
            />
          </div>
          <div className="flex flex-col space-y-2">
            <Label htmlFor="avg-speed-interval">Avg Speed Interval</Label>
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
            <p className="text-xs text-muted-foreground">
              The interval to calculate the average download speed. <br />
              Longer intervals may consume more memory.
            </p>
          </div>
        </div>
      </div>
      <div className="mt-2 flex flex-1 justify-end">
        <Button className="w-full md:w-auto" type="submit">Submit</Button>
      </div>
    </form>
  );
}
