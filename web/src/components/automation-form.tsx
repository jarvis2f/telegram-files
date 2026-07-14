import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import {
  type Auto,
  type AutoDownloadRule,
  type AutoTransferRule,
  DuplicationPolicies,
  type DuplicationPolicy,
  type FileType,
  TransferPolices,
  type TransferPolicy,
} from "@/lib/types";
import React, { useId, useMemo, useState } from "react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import {
  Check,
  ChevronsUpDown,
  Download,
  ExternalLink,
  FolderSync,
  PackageSearch,
  X,
} from "lucide-react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import {
  HoverCard,
  HoverCardContent,
  HoverCardTrigger,
} from "@/components/ui/hover-card";
import {
  Command,
  CommandGroup,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { useMutationObserver } from "@/hooks/use-mutation-observer";
import { cn } from "@/lib/utils";
import { Textarea } from "@/components/ui/textarea";
import Link from "next/link";

interface AutomationFormProps {
  auto: Auto;
  onChange: (auto: Auto) => void;
}

function AutomationToggleSection({
  id,
  title,
  description,
  checked,
  icon,
  onCheckedChange,
  children,
}: React.PropsWithChildren<{
  id: string;
  title: string;
  description: string;
  checked: boolean;
  icon: React.ReactNode;
  onCheckedChange: (checked: boolean) => void;
}>) {
  return (
    <section className="rounded-lg border bg-card p-4 shadow-sm">
      <div className="flex items-start justify-between gap-4">
        <div className="flex min-w-0 gap-3">
          <div className="mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-md bg-primary/10 text-primary">
            {icon}
          </div>
          <div className="min-w-0">
            <Label htmlFor={id} className="font-semibold">
              {title}
            </Label>
            <p className="mt-1 text-sm text-muted-foreground">{description}</p>
          </div>
        </div>
        <Switch id={id} checked={checked} onCheckedChange={onCheckedChange} />
      </div>
      {children && <div className="mt-4 flex flex-col gap-4">{children}</div>}
    </section>
  );
}

function HintPanel({ children }: React.PropsWithChildren) {
  return (
    <div className="rounded-md border bg-muted/30 p-4 text-sm text-muted-foreground">
      <div className="flex flex-col gap-3">{children}</div>
    </div>
  );
}

function HintLine({ children }: React.PropsWithChildren) {
  return (
    <div className="flex items-start gap-3">
      <span className="mt-2 size-1.5 shrink-0 rounded-full bg-primary" />
      <div className="leading-6">{children}</div>
    </div>
  );
}

export default function AutomationForm({
  auto,
  onChange,
}: AutomationFormProps) {
  return (
    <div className="flex flex-col gap-4">
      <AutomationToggleSection
        id="enable-preload"
        title="Enable Preload"
        description="Index chat files in advance so they can be searched offline."
        checked={auto.preload.enabled}
        icon={<PackageSearch />}
        onCheckedChange={(checked) => {
          onChange({
            ...auto,
            preload: {
              ...auto.preload,
              enabled: checked,
            },
          });
        }}
      >
        {auto.preload.enabled && (
          <HintPanel>
            <HintLine>
              All files will be loaded into the index but not downloaded.
            </HintLine>
          </HintPanel>
        )}
      </AutomationToggleSection>
      <AutomationToggleSection
        id="enable-auto-download"
        title="Enable Auto Download"
        description="Automatically download files that match the configured rules."
        checked={auto.download.enabled}
        icon={<Download />}
        onCheckedChange={(checked) => {
          onChange({
            ...auto,
            download: {
              ...auto.download,
              enabled: checked,
            },
          });
        }}
      >
        {auto.download.enabled && (
          <>
            <HintPanel>
              <HintLine>
                Matched files will be downloaded automatically.
              </HintLine>
              <HintLine>
                If download history is enabled, historical messages are handled
                before new incoming files.
              </HintLine>
              <HintLine>
                Download order:
                <Badge variant="secondary" className="ml-1 font-normal">
                  {"Photo -> Video -> Audio -> File"}
                </Badge>
              </HintLine>
            </HintPanel>
            <DownloadRule
              value={auto.download.rule}
              onChange={(value) => {
                onChange({
                  ...auto,
                  download: {
                    ...auto.download,
                    rule: value,
                  },
                });
              }}
            />
          </>
        )}
      </AutomationToggleSection>
      <AutomationToggleSection
        id="enable-transfer"
        title="Enable Transfer"
        description="Move downloaded files to a destination folder automatically."
        checked={auto.transfer.enabled}
        icon={<FolderSync />}
        onCheckedChange={(checked) => {
          onChange({
            ...auto,
            transfer: {
              ...auto.transfer,
              enabled: checked,
            },
          });
        }}
      >
        {auto.transfer.enabled && (
          <>
            <HintPanel>
              <HintLine>
                Downloaded files will be transferred to the specified location.
              </HintLine>
            </HintPanel>
            <TransferRule
              value={auto.transfer.rule}
              onChange={(value) => {
                onChange({
                  ...auto,
                  transfer: {
                    ...auto.transfer,
                    rule: value,
                  },
                });
              }}
            />
          </>
        )}
      </AutomationToggleSection>
    </div>
  );
}

interface DownloadRuleProps {
  value: AutoDownloadRule;
  onChange: (value: AutoDownloadRule) => void;
}

function DownloadRule({ value, onChange }: DownloadRuleProps) {
  const handleQueryChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange({
      ...value,
      query: e.target.value,
    });
  };

  const handleFilterExprChange = (
    e: React.ChangeEvent<HTMLTextAreaElement>,
  ) => {
    onChange({
      ...value,
      filterExpr: e.target.value,
    });
  };

  const handleFileTypeSelect = (type: string) => {
    if (value.fileTypes.includes(type as Exclude<FileType, "media">)) {
      return;
    }

    onChange({
      ...value,
      fileTypes: [...value.fileTypes, type as Exclude<FileType, "media">],
    });
  };

  const removeFileType = (typeToRemove: string) => {
    onChange({
      ...value,
      fileTypes: value.fileTypes.filter((type) => type !== typeToRemove),
    });
  };

  return (
    <Accordion type="single" collapsible className="rounded-lg border bg-card">
      <AccordionItem value="advanced" className="border-none px-4">
        <AccordionTrigger className="hover:no-underline">
          Advanced
        </AccordionTrigger>
        <AccordionContent>
          <div className="flex flex-col gap-4 rounded-md border bg-muted/20 p-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="query-keyword">Query Keyword</Label>
              <Input
                id="query-keyword"
                type="text"
                className="w-full"
                placeholder="Enter a keyword to filter files"
                value={value.query}
                onChange={handleQueryChange}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="filter-expr">
                Filter Expression
                <Link
                  href="https://github.com/jarvis2f/telegram-files/blob/main/misc/filter-expression-guide.md"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="ml-2 inline-flex items-center gap-1 text-sm text-primary hover:underline"
                >
                  Learn more
                  <ExternalLink className="size-3" />
                </Link>
              </Label>
              <Textarea
                id="filter-expr"
                className="min-h-24 w-full"
                placeholder="Enter a filter expression (e.g., str:contains(content.text.text, 'Hello') and id > 1000)"
                value={value.filterExpr}
                onChange={handleFilterExprChange}
              />
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="fileTypes">Filter File Types</Label>
              <Select onValueChange={handleFileTypeSelect}>
                <SelectTrigger id="fileTypes">
                  <SelectValue placeholder="Select File Types" />
                </SelectTrigger>
                <SelectContent>
                  {["photo", "video", "audio", "file"].map((type) => (
                    <SelectItem key={type} value={type}>
                      {type}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <div className="flex min-h-8 flex-wrap gap-2">
                {value.fileTypes.length > 0 ? (
                  value.fileTypes.map((type) => (
                    <Badge
                      key={type}
                      className="flex items-center gap-1 px-2 py-1 capitalize"
                      variant="secondary"
                    >
                      {type}
                      <button
                        type="button"
                        aria-label={`Remove ${type}`}
                        className="rounded-sm text-muted-foreground transition hover:text-foreground"
                        onClick={() => removeFileType(type)}
                      >
                        <X className="size-3" />
                      </button>
                    </Badge>
                  ))
                ) : (
                  <span className="text-sm text-muted-foreground">
                    No file types selected
                  </span>
                )}
              </div>
            </div>

            <div className="rounded-md border bg-background p-4">
              <div className="flex items-center justify-between">
                <Label htmlFor="download-history">Download History</Label>
                <Switch
                  id="download-history"
                  checked={value.downloadHistory}
                  onCheckedChange={(checked) =>
                    onChange({
                      ...value,
                      downloadHistory: checked,
                    })
                  }
                />
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                If enabled, all historical files will be downloaded. Otherwise,
                only new files will be downloaded.
              </p>
            </div>
            <div className="rounded-md border bg-background p-4">
              <div className="flex items-center justify-between">
                <Label htmlFor="download-comment-files">
                  Download comment files
                </Label>
                <Switch
                  id="download-comment-files"
                  checked={value.downloadCommentFiles}
                  onCheckedChange={(checked) =>
                    onChange({
                      ...value,
                      downloadCommentFiles: checked,
                    })
                  }
                />
              </div>
            </div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
}

interface TransferRuleProps {
  value: AutoTransferRule;
  onChange: (value: AutoTransferRule) => void;
}

function TransferRule({ value, onChange }: TransferRuleProps) {
  const captionNameId = useId();
  const handleTransferRuleChange = (changes: Partial<AutoTransferRule>) => {
    onChange({
      ...value,
      ...changes,
    });
  };

  return (
    <Accordion type="single" collapsible className="rounded-lg border bg-card">
      <AccordionItem value="advanced" className="border-none px-4">
        <AccordionTrigger className="hover:no-underline">
          Advanced
        </AccordionTrigger>
        <AccordionContent>
          <div className="flex flex-col gap-4 rounded-md border bg-muted/20 p-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="destination">
                Destination folder for auto transfer
              </Label>
              <Input
                id="destination"
                type="text"
                className="w-full"
                placeholder="Enter a destination folder"
                value={value.destination}
                onChange={(e) => {
                  handleTransferRuleChange({ destination: e.target.value });
                }}
              />
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="transfer-policy">Transfer Policy</Label>
              <PolicySelect
                policyType="transfer"
                value={value.transferPolicy}
                onChange={(policy) =>
                  handleTransferRuleChange({
                    transferPolicy: policy as TransferPolicy,
                  })
                }
              />
            </div>

            {value.transferPolicy === "GROUP_BY_AI" && (
              <div className="flex flex-col gap-2">
                <Label htmlFor="prompt-template">
                  AI Classification Prompt Template
                </Label>
                <Textarea
                  id="prompt-template"
                  className="min-h-28 w-full"
                  rows={4}
                  placeholder="Enter a prompt template to guide AI classification"
                  value={value.extra.promptTemplate || ""}
                  onChange={(e) =>
                    handleTransferRuleChange({
                      extra: {
                        ...value.extra,
                        promptTemplate: e.target.value,
                      },
                    })
                  }
                />
              </div>
            )}

            <div className="flex flex-col gap-2">
              <Label htmlFor="duplication-policy">Duplication Policy</Label>
              <PolicySelect
                policyType="duplication"
                value={value.duplicationPolicy}
                onChange={(policy) =>
                  handleTransferRuleChange({
                    duplicationPolicy: policy as DuplicationPolicy,
                  })
                }
              />
            </div>

            <div className="rounded-md border bg-background p-4">
              <div className="flex items-center justify-between">
                <Label htmlFor="transfer-history">Transfer History</Label>
                <Switch
                  id="transfer-history"
                  checked={value.transferHistory}
                  onCheckedChange={(checked) =>
                    handleTransferRuleChange({ transferHistory: checked })
                  }
                />
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                Transfer files that are already downloaded to the specified
                location.
              </p>
            </div>

            <div className="rounded-md border bg-background p-4">
              <div className="flex items-center justify-between">
                <Label htmlFor={captionNameId}>Add caption to file name</Label>
                <Switch
                  id={captionNameId}
                  checked={value.useCaptionName ?? false}
                  onCheckedChange={(checked) =>
                    handleTransferRuleChange({ useCaptionName: checked })
                  }
                />
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                Append the post caption to the transferred file name (e.g.
                name_caption.jpg). Falls back to the original name when the post
                has no caption.
              </p>
            </div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
}

const PolicyLegends: Record<
  TransferPolicy | DuplicationPolicy,
  {
    title: string;
    description: string | React.ReactNode;
  }
> = {
  DIRECT: {
    title: "Direct",
    description: "Transfer files directly to the destination folder.",
  },
  GROUP_BY_CHAT: {
    title: "Group by Chat",
    description: (
      <div className="flex flex-col gap-2">
        <p className="text-sm">
          Transfer files to folders based on the chat name.
        </p>
        <p className="text-xs text-muted-foreground">Example:</p>
        <p className="inline-block rounded bg-muted px-2 py-1 text-xs text-muted-foreground">
          {"/${Destination Folder}/${Telegram Id}/${Chat Id}/${file}"}
        </p>
      </div>
    ),
  },
  GROUP_BY_TYPE: {
    title: "Group by Type",
    description: (
      <div className="flex flex-col gap-2">
        <p className="text-sm">
          Transfer files to folders based on the file type. <br />
          All account files will be transferred to the same folder.
        </p>
        <p className="text-xs text-muted-foreground">Example:</p>
        <p className="inline-block rounded bg-muted px-2 py-1 text-xs text-muted-foreground">
          {"/${Destination Folder}/${File Type}/${file}"}
        </p>
      </div>
    ),
  },
  GROUP_BY_AI: {
    title: "Group by AI",
    description: (
      <div className="flex flex-col gap-2">
        <p className="text-sm">
          Use AI to classify files and transfer them to different folders based
          on their content.
        </p>
        <p className="text-sm">
          You can write a prompt to guide the AI in classifying the files. Like:
        </p>
        <p className="inline-block rounded bg-muted px-2 py-1 text-xs text-muted-foreground">
          Classify the following file into one of the categories: Work,
          Personal, Important, Others. <br />
          File name: {"{file_name}"} <br />
          Respond with only the category name.
        </p>
        <p className="text-sm">
          You can use {"{FileRecord Field}"} in the prompt to provide more
          context to the AI.
        </p>
      </div>
    ),
  },
  OVERWRITE: {
    title: "Overwrite",
    description:
      "If destination exists same name file, move and overwrite the file.",
  },
  SKIP: {
    title: "Skip",
    description:
      "If destination exists same name file, skip the file, nothing to do.",
  },
  RENAME: {
    title: "Rename",
    description:
      "This strategy will rename the file, add a serial number after the file name, and then move the file to the destination folder",
  },
  HASH: {
    title: "Hash",
    description:
      "Calculate the hash (md5) of the file and compare with the existing file, if the hash is the same, delete the original file and set the local path to the existing file, otherwise, move the file",
  },
};

interface PolicySelectProps {
  policyType: "transfer" | "duplication";
  value?: string;
  onChange: (value: string) => void;
}

function PolicySelect({ policyType, value, onChange }: PolicySelectProps) {
  const [open, setOpen] = useState(false);
  const polices =
    policyType === "transfer" ? TransferPolices : DuplicationPolicies;
  const [peekedPolicy, setPeekedPolicy] = useState<string>(value ?? polices[0]);

  const peekPolicyLegend = useMemo(() => {
    return PolicyLegends[peekedPolicy as TransferPolicy | DuplicationPolicy];
  }, [peekedPolicy]);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-label="Select a policy"
          className="w-full justify-between"
        >
          {value ?? "Select a policy..."}
          <ChevronsUpDown className="opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[280px] p-0" modal={true}>
        <HoverCard>
          <HoverCardContent
            side="top"
            align="start"
            forceMount
            className="min-h-[150px] w-auto min-w-64 max-w-[380px] border bg-popover"
          >
            <div className="flex flex-col gap-2">
              <h4 className="font-medium leading-none">
                {peekPolicyLegend?.title}
              </h4>
              {typeof peekPolicyLegend?.description === "string" ? (
                <p className="text-sm text-muted-foreground">
                  {peekPolicyLegend?.description ?? ""}
                </p>
              ) : (
                peekPolicyLegend?.description
              )}
            </div>
          </HoverCardContent>
          <Command>
            <CommandList className="h-[var(--cmdk-list-height)] max-h-[400px]">
              <HoverCardTrigger />
              <CommandGroup>
                {polices.map((policy) => (
                  <PolicyItem
                    key={policy}
                    policy={policy ?? ""}
                    isSelected={value === policy}
                    onPeek={setPeekedPolicy}
                    onSelect={() => {
                      onChange(policy);
                      setOpen(false);
                    }}
                  />
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </HoverCard>
      </PopoverContent>
    </Popover>
  );
}

interface PolicyItemProps {
  policy: string;
  isSelected: boolean;
  onSelect: () => void;
  onPeek: (policy: string) => void;
}

function PolicyItem({ policy, isSelected, onSelect, onPeek }: PolicyItemProps) {
  const ref = React.useRef<HTMLDivElement>(null);

  useMutationObserver(ref, (mutations) => {
    mutations.forEach((mutation) => {
      if (
        mutation.type === "attributes" &&
        mutation.attributeName === "aria-selected" &&
        ref.current?.getAttribute("aria-selected") === "true"
      ) {
        onPeek(policy);
      }
    });
  });

  return (
    <CommandItem
      key={policy}
      onSelect={onSelect}
      ref={ref}
      className="data-[selected=true]:bg-accent data-[selected=true]:text-accent-foreground"
    >
      {policy}
      <Check
        className={cn("ml-auto", isSelected ? "opacity-100" : "opacity-0")}
      />
    </CommandItem>
  );
}
