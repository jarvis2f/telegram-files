import { Bell } from "lucide-react";
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

export default function SettingsForm() {
  const { settings, setSetting, updateSettings } = useSettings();

  const imageLoadSizeOptions = [
    { value: "s", label: "box 100x100" },
    { value: "m", label: "box 320x320" },
    { value: "x", label: "box 800x800" },
    { value: "y", label: "box 1280x1280" },
    { value: "w", label: "box 2560x2560" },
    { value: "a", label: "crop 160x160" },
    { value: "b", label: "crop 320x320" },
    { value: "c", label: "crop 640x640" },
    { value: "d", label: "crop 1280x1280" },
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
        <p className="rounded-md bg-gray-50 p-2 text-sm text-muted-foreground shadow">
          <Bell className="mr-2 inline-block h-4 w-4" />
          These settings will be applied to all accounts.
        </p>
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
            <Label htmlFor="need-load-preview-images">
              Need Load Preview Images
            </Label>
            <Checkbox
              id="need-load-preview-images"
              checked={settings?.needToLoadImages === "true"}
              onCheckedChange={(checked) => {
                void setSetting("needToLoadImages", String(checked));
              }}
            />
          </div>
          {settings?.needToLoadImages === "true" && (
            <div className="space-y-2">
              <Label htmlFor="image-load-size">Load Size</Label>
              <Select
                value={settings.imageLoadSize}
                onValueChange={(v) => void setSetting("imageLoadSize", v)}
              >
                <SelectTrigger id="image-load-size">
                  <SelectValue placeholder="Select Image Load Size" />
                </SelectTrigger>
                <SelectContent>
                  {imageLoadSizeOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                The size of the image to load in the browser.
              </p>
            </div>
          )}
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
        </div>
      </div>
      <div className="mt-2 flex flex-1 justify-end">
        <Button type="submit">Submit</Button>
      </div>
    </form>
  );
}
