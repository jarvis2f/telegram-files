"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { Loader2, ShieldCheck } from "lucide-react";

import { request } from "@/lib/api";
import { describeShareError } from "@/lib/share-errors";
import type { TelegramFile } from "@/lib/types";
import { toast } from "@/hooks/use-toast";
import { useSharePublicationPolicy } from "@/hooks/use-share-publication-policy";
import { categoryForFile } from "@/lib/share-publication-policy";
import { defaultShareTags } from "@/lib/share-tags";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  SourceAccessSelect,
  type AccessScope,
} from "@/components/source-access-select";

export function ShareResourceDialog({
  file,
  open,
  onOpenChange,
}: {
  file: TelegramFile;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const [title, setTitle] = useState(
    file.fileName || file.caption || "Telegram file",
  );
  const policy = useSharePublicationPolicy();
  const [description, setDescription] = useState(file.caption ?? "");
  const [tags, setTags] = useState(defaultShareTags(file));
  const [category, setCategory] = useState<string>(categoryForFile(file, policy));
  const [scope, setScope] = useState<AccessScope>("OWNER_ONLY");
  const [publicMessageUrl, setPublicMessageUrl] = useState("");
  const downloaded = file.downloadStatus === "completed";
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setTitle(file.fileName || file.caption || "Telegram file");
    setDescription(file.caption ?? "");
    setTags(defaultShareTags(file));
    setCategory(categoryForFile(file, policy));
  }, [file, open, policy]);

  async function publish() {
    if (!downloaded) return;
    setSubmitting(true);
    try {
      const isEditing = Boolean(file.sharedResourceId);
      const endpoint = isEditing ? `/share/resources/${file.sharedResourceId}` : "/share/resources";
      const method = isEditing ? "PUT" : "POST";
      const result = await request<{
        status: string;
        lastErrorCode: string | null;
      }>(endpoint, {
        method,
        body: JSON.stringify({
          ...(isEditing ? {} : { fileUniqueId: file.uniqueId }),
          title,
          description: description || null,
          tags: tags
            .split(",")
            .map((tag) => tag.trim())
            .filter(Boolean),
          category: category || null,
          accessScope: scope,
          publicMessageUrl: scope === "PUBLIC" ? publicMessageUrl : null,
          immediateReseed: true,
          indexOnly: false,
          autoDownloadOnDemand: false,
        }),
      });
      const failed = result.status.endsWith("_FAILED");
      toast({
        variant: failed
          ? "error"
          : result.status === "PUBLISHED"
            ? "success"
            : "info",
        title: failed
          ? "Publication failed"
          : result.status === "PUBLISHED"
            ? "Resource published"
            : "Publication queued",
        description: failed
          ? describeShareError(result.lastErrorCode)
          : result.status === "PUBLISHED"
            ? "The public index can now discover this resource."
            : "The node will retry when the platform is reachable.",
      });
      onOpenChange(false);
    } catch (error) {
      toast({
        variant: "error",
        title: "Publication failed",
        description:
          error instanceof Error
            ? error.message
            : "The resource could not be published.",
      });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90dvh] max-w-2xl grid-rows-[auto_minmax(0,1fr)] gap-0 overflow-hidden p-0">
        <div className="flex items-center gap-2 border-b px-6 pb-5 pt-6 text-sm font-medium text-muted-foreground">
          <Image
            src="/telegram-seed.svg"
            alt=""
            aria-hidden="true"
            width={20}
            height={20}
            className="size-5 rounded-full bg-white"
          />
          Index publication
        </div>

        <form
          className="flex min-h-0 flex-col overflow-hidden"
          onSubmit={(event) => {
            event.preventDefault();
            void publish();
          }}
        >
          <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto overscroll-contain px-6 py-5">
            <DialogHeader className="gap-2">
              <DialogTitle>Share metadata, keep the locator local.</DialogTitle>
              <DialogDescription>
                The platform receives an opaque capability and safe file
                metadata. Chat ID, message ID, account identity, and local paths
                never leave this node.
              </DialogDescription>
            </DialogHeader>

            <div className="grid gap-4 md:grid-cols-2">
              <label className="flex flex-col gap-2 md:col-span-2">
                <span className="text-sm font-medium">Title</span>
                <Input
                  value={title}
                  maxLength={255}
                  required
                  onChange={(event) => setTitle(event.target.value)}
                />
              </label>
              <label className="flex flex-col gap-2 md:col-span-2">
                <span className="text-sm font-medium">Description</span>
                <Textarea
                  value={description}
                  maxLength={4096}
                  rows={4}
                  onChange={(event) => setDescription(event.target.value)}
                />
              </label>
              <label className="flex flex-col gap-2">
                <span className="text-sm font-medium">Tags</span>
                <Input
                  value={tags}
                  placeholder="document, archive"
                  onChange={(event) => setTags(event.target.value)}
                />
              </label>
              <label className="flex flex-col gap-2">
                <span className="text-sm font-medium">Category</span>
                <Select
                  value={category}
                  onValueChange={setCategory}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {policy.categories.map((item) => (
                        <SelectItem key={item.id} value={item.id}>
                          {item.label}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </label>
              <div className="flex flex-col gap-2 md:col-span-2">
                <Label htmlFor={`scope-${file.uniqueId}`}>Source access</Label>
                <SourceAccessSelect
                  id={`scope-${file.uniqueId}`}
                  value={scope}
                  onValueChange={setScope}
                />
              </div>
              {scope === "PUBLIC" && (
                <label className="flex flex-col gap-2 md:col-span-2">
                  <span className="text-sm font-medium">
                    Public Telegram message URL
                  </span>
                  <Input
                    type="url"
                    value={publicMessageUrl}
                    required
                    placeholder="https://t.me/channel/42"
                    onChange={(event) =>
                      setPublicMessageUrl(event.target.value)
                    }
                  />
                </label>
              )}
            </div>

            <div className="flex items-start gap-3 rounded-lg bg-muted p-4 text-sm text-muted-foreground">
              <ShieldCheck aria-hidden="true" />
              <span>
                {file.downloadStatus === "completed"
                  ? "Downloaded source"
                  : "Remote-only source"}{" "}
                · {file.fileName}
              </span>
            </div>
          </div>

          <DialogFooter className="shrink-0 gap-2 border-t bg-background px-6 py-4">
            <Button
              type="button"
              variant="ghost"
              onClick={() => onOpenChange(false)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              disabled={
                submitting || title.trim().length === 0 || !downloaded
              }
            >
              {submitting && (
                <Loader2 data-icon="inline-start" className="animate-spin" />
              )}
              Publish resource
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
