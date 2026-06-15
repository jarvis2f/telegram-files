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
import { Check, ChevronsUpDown, X } from "lucide-react";
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

export default function AutomationForm({
  auto,
  onChange,
}: AutomationFormProps) {
  return (
    <div className="space-y-4">
      <div className="space-y-4 rounded-md border border-gray-200 p-4 dark:border-gray-700">
        <div className="flex items-center justify-between">
          <Label htmlFor="enable-preload">Enable Preload</Label>
          <Switch
            id="enable-preload"
            checked={auto.preload.enabled}
            onCheckedChange={(checked) => {
              onChange({
                ...auto,
                preload: {
                  ...auto.preload,
                  enabled: checked,
                },
              });
            }}
          />
        </div>
        {auto.preload.enabled && (
          <div className="space-y-4 rounded-md border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800">
            <div className="flex items-start">
              <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
              <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                This will enable preload for this chat. All files will be
                loaded, but not downloaded, then you can search offline.
              </p>
            </div>
          </div>
        )}
      </div>
      <div className="space-y-4 rounded-md border border-gray-200 p-4 dark:border-gray-700">
        <div className="flex items-center justify-between">
          <Label htmlFor="enable-auto-download">Enable Auto Download</Label>
          <Switch
            id="enable-auto-download"
            checked={auto.download.enabled}
            onCheckedChange={(checked) => {
              onChange({
                ...auto,
                download: {
                  ...auto.download,
                  enabled: checked,
                },
              });
            }}
          />
        </div>
        {auto.download.enabled && (
          <>
            <div className="space-y-4 rounded-md border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800">
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  This will enable auto download for this chat. Files will be
                  downloaded automatically.
                </p>
              </div>
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  If you enable download history, the files in historical
                  messages will be downloaded first, and then files in new
                  messages will be downloaded automatically.
                </p>
              </div>
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  Download Order:
                  <span className="ml-1 rounded bg-blue-100 px-2 text-blue-700 dark:bg-blue-800 dark:text-blue-200">
                    {"Photo -> Video -> Audio -> File"}
                  </span>
                </p>
              </div>
            </div>
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
      </div>
      <div className="space-y-4 rounded-md border border-gray-200 p-4 dark:border-gray-700">
        <div className="flex items-center justify-between">
          <Label htmlFor="enable-transfer">Enable Transfer</Label>
          <Switch
            id="enable-transfer"
            checked={auto.transfer.enabled}
            onCheckedChange={(checked) => {
              onChange({
                ...auto,
                transfer: {
                  ...auto.transfer,
                  enabled: checked,
                },
              });
            }}
          />
        </div>
        {auto.transfer.enabled && (
          <>
            <div className="space-y-4 rounded-md border border-gray-200 bg-gray-50 p-4 dark:border-gray-700 dark:bg-gray-800">
              <div className="flex items-start">
                <span className="mr-3 mt-1.5 h-3 w-2 flex-shrink-0 rounded-full bg-cyan-400"></span>
                <p className="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  This will enable auto transfer for this chat. Downloaded files
                  will be transferred to the specified location automatically.
                </p>
              </div>
            </div>
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
      </div>
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
    <Accordion type="single" collapsible>
      <AccordionItem value="advanced">
        <AccordionTrigger className="hover:no-underline">
          Advanced
        </AccordionTrigger>
        <AccordionContent>
          <div className="flex flex-col space-y-4 rounded-md border p-4 shadow">
            <div className="flex flex-col space-y-2">
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
            <div className="flex flex-col space-y-2">
              <Label htmlFor="filter-expr">
                Filter Expression
                <Link
                  href="https://github.com/jarvis2f/telegram-files/blob/main/misc/filter-expression-guide.md"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="ml-2 text-sm text-blue-600 hover:underline"
                >
                  (Learn more)
                </Link>
              </Label>
              <Textarea
                id="filter-expr"
                className="w-full"
                placeholder="Enter a filter expression (e.g., str:contains(content.text.text, 'Hello') and id > 1000)"
                value={value.filterExpr}
                onChange={handleFilterExprChange}
              />
            </div>

            <div className="flex flex-col space-y-2">
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

              <div className="mt-2 flex flex-wrap gap-2">
                {value.fileTypes.map((type) => (
                  <Badge
                    key={type}
                    className="flex items-center gap-1 px-2 py-1"
                    variant="secondary"
                  >
                    {type}
                    <X
                      className="h-3 w-3 cursor-pointer"
                      onClick={() => removeFileType(type)}
                    />
                  </Badge>
                ))}
              </div>
            </div>

            <div className="rounded-md border p-4">
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
            <div className="rounded-md border p-4">
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
    <Accordion type="single" collapsible>
      <AccordionItem value="advanced">
        <AccordionTrigger className="hover:no-underline">
          Advanced
        </AccordionTrigger>
        <AccordionContent>
          <div className="flex flex-col space-y-4 rounded-md border p-4 shadow">
            <div className="flex flex-col space-y-2">
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

            <div className="flex flex-col space-y-2">
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
              <div className="flex flex-col space-y-2">
                <Label htmlFor="prompt-template">
                  AI Classification Prompt Template
                </Label>
                <Textarea
                  id="prompt-template"
                  className="w-full"
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

            <div className="flex flex-col space-y-2">
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

            <div className="rounded-md border p-4">
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

            <div className="rounded-md border p-4">
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
      <div className="space-y-2">
        <p className="text-sm">
          Transfer files to folders based on the chat name.
        </p>
        <p className="text-xs text-muted-foreground">Example:</p>
        <p className="inline-block rounded bg-gray-100 p-1 text-xs text-muted-foreground dark:bg-gray-800 dark:text-gray-300">
          {"/${Destination Folder}/${Telegram Id}/${Chat Id}/${file}"}
        </p>
      </div>
    ),
  },
  GROUP_BY_TYPE: {
    title: "Group by Type",
    description: (
      <div className="space-y-2">
        <p className="text-sm">
          Transfer files to folders based on the file type. <br />
          All account files will be transferred to the same folder.
        </p>
        <p className="text-xs text-muted-foreground">Example:</p>
        <p className="inline-block rounded bg-gray-100 p-1 text-xs text-muted-foreground dark:bg-gray-800 dark:text-gray-300">
          {"/${Destination Folder}/${File Type}/${file}"}
        </p>
      </div>
    ),
  },
  GROUP_BY_AI: {
    title: "Group by AI",
    description: (
      <div className="space-y-2">
        <p className="text-sm">
          Use AI to classify files and transfer them to different folders based
          on their content.
        </p>
        <p className="text-sm">
          You can write a prompt to guide the AI in classifying the files. Like:
        </p>
        <p className="inline-block rounded bg-gray-100 p-1 text-xs text-muted-foreground dark:bg-gray-800 dark:text-gray-300">
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
      <PopoverContent align="start" className="w-[250px] p-0" modal={true}>
        <HoverCard>
          <HoverCardContent
            side="top"
            align="start"
            forceMount
            className="min-h-[150px] w-auto min-w-64 max-w-[380px]"
          >
            <div className="grid gap-2">
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
      className="data-[selected=true]:bg-primary data-[selected=true]:text-primary-foreground"
    >
      {policy}
      <Check
        className={cn("ml-auto", isSelected ? "opacity-100" : "opacity-0")}
      />
    </CommandItem>
  );
}
