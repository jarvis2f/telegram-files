import { useTelegramMethod } from "@/hooks/use-telegram-method";
import useSWR from "swr";
import { request } from "@/lib/api";
import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Textarea } from "@/components/ui/textarea";
import {
  Check,
  ChevronsUpDown,
  CircleAlert,
  Copy,
  Ellipsis,
  Loader2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { CommandLoading } from "cmdk";
import { useDebounce } from "use-debounce";
import { useCopyToClipboard } from "@/hooks/use-copy-to-clipboard";
import Link from "next/link";
import { useI18n } from "@/hooks/use-i18n";

interface TelegramMethodData {
  methods: string[];
}

interface TelegramMethodParameters {
  parameters: Record<string, any>;
}

interface MethodError {
  message: string;
  values?: Record<string, string | number>;
}

export default function DebugTelegramMethod() {
  const { t } = useI18n();
  const { triggerMethod, isMethodExecuting, lastMethodCode, lastMethodResult } =
    useTelegramMethod();
  const [isDeMethodExecuting] = useDebounce(isMethodExecuting, 500, {
    leading: true,
  });
  const [, copyToClipboard] = useCopyToClipboard();
  const [method, setMethod] = useState<string>("");

  const [parameters, setParameters] = useState<any>({});
  const [parametersJson, setParametersJson] = useState<string>("");
  const [error, setError] = useState<MethodError | null>(null);
  const [open, setOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");

  const { data: methodData, isLoading: isMethodsLoading } = useSWR<
    TelegramMethodData,
    Error
  >("/telegram/api/methods", {
    // 添加缓存以提升性能
    revalidateOnFocus: false,
    revalidateOnReconnect: false,
    dedupingInterval: 60000,
  });

  // 使用 useMemo 缓存过滤后的方法列表
  // eslint-disable-next-line react-hooks/preserve-manual-memoization
  const filteredMethods = useMemo(() => {
    if (!methodData?.methods || isMethodsLoading) return [];

    return methodData.methods
      .filter((m) => m.toLowerCase().includes(searchQuery.toLowerCase()))
      .slice(0, 100)
      .sort();
  }, [isMethodsLoading, methodData?.methods, searchQuery]);

  const methodDocUrl = useMemo(() => {
    if (!method) return "";
    const methodSlug = method.replace(/([a-z])([A-Z])/g, "$1_$2").toLowerCase();
    return `https://core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1${methodSlug}.html`;
  }, [method]);

  const { isLoading: isParametersLoading } = useSWR<
    TelegramMethodParameters,
    Error
  >(method ? `/telegram/api/${method}/parameters` : null, request, {
    revalidateOnFocus: false,
    onSuccess: (data) => {
      const defaultParams = data.parameters;
      setParameters(defaultParams);
      setParametersJson(JSON.stringify(defaultParams, null, 2));
    },
  });

  const handleMethodChange = (value: string) => {
    setMethod(value);
    setError(null);
    setOpen(false);
  };

  const handleParametersChange = (value: string) => {
    setParametersJson(value);
    try {
      setParameters(JSON.parse(value));
      setError(null);
    } catch (failure) {
      setError(
        failure instanceof Error
          ? {
              message: "Invalid JSON format: {message}",
              values: { message: failure.message },
            }
          : { message: "Invalid JSON format" },
      );
    }
  };

  const handleExecute = async () => {
    try {
      await triggerMethod({
        data: parameters,
        method,
      });
      setError(null);
    } catch (failure) {
      setError({
        message:
          failure instanceof Error ? failure.message : "An error occurred",
      });
    }
  };

  const isLoading = isMethodsLoading || isParametersLoading;
  const isExecuteDisabled = !method || !!error || isMethodExecuting;

  return (
    <Card className="no-scrollbar mx-auto h-full w-full overflow-y-scroll">
      <CardHeader>
        <CardTitle>{t("Telegram API Debug")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {/* 选择方法 */}
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <label className="text-sm font-medium">{t("Method")}</label>
            {isMethodsLoading && (
              <div className="flex items-center text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-3 w-3 animate-spin" />
                {t("Loading methods...")}
              </div>
            )}
          </div>
          <Popover open={open} onOpenChange={setOpen}>
            <PopoverTrigger asChild>
              <Button
                variant="outline"
                role="combobox"
                aria-expanded={open}
                className="w-full justify-between"
                disabled={isLoading}
              >
                {method ? method : t("Select a method...")}
                <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
              </Button>
            </PopoverTrigger>
            <PopoverContent className="w-full p-0" modal={true} align="start">
              <Command shouldFilter={false}>
                <CommandInput
                  placeholder={t("Search method...")}
                  value={searchQuery}
                  onValueChange={setSearchQuery}
                />
                <CommandList className="relative">
                  {isMethodsLoading && (
                    <CommandLoading>
                      <div className="absolute left-1/2 top-1/2 -translate-x-2 -translate-y-2 transform">
                        <Ellipsis className="h-4 w-4 animate-pulse" />
                      </div>
                    </CommandLoading>
                  )}
                  {!isMethodsLoading && filteredMethods.length === 0 && (
                    <CommandEmpty>{t("No methods found.")}</CommandEmpty>
                  )}
                  {!isMethodExecuting && searchQuery.length === 0 && (
                    <>
                      <CommandGroup>
                        <CommandItem disabled={true}>
                          <CircleAlert className="h-3 w-3" />
                          {t("Only the first 100 methods are shown.")}
                        </CommandItem>
                      </CommandGroup>
                      <CommandSeparator />
                    </>
                  )}
                  <CommandGroup>
                    {filteredMethods.map((m) => (
                      <CommandItem
                        key={m}
                        value={m}
                        onSelect={handleMethodChange}
                      >
                        <Check
                          className={cn(
                            "h-4 w-4",
                            method === m ? "opacity-100" : "opacity-0",
                          )}
                        />
                        {m}
                      </CommandItem>
                    ))}
                  </CommandGroup>
                </CommandList>
              </Command>
            </PopoverContent>
          </Popover>
          {method && (
            <Link
              href={methodDocUrl}
              target="_blank"
              className="text-xs text-blue-500 underline"
            >
              {t("{method} Documentation", { method })}
            </Link>
          )}
        </div>

        {/* 编辑参数 */}
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <label className="text-sm font-medium">{t("Parameters")}</label>
            {isParametersLoading && (
              <div className="flex items-center text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-3 w-3 animate-spin" />
                {t("Loading parameters...")}
              </div>
            )}
          </div>
          <Textarea
            value={parametersJson}
            onChange={(e) => handleParametersChange(e.target.value)}
            placeholder={t("Enter parameters in JSON format")}
            className="h-48 font-mono text-sm"
            disabled={!method || isLoading}
          />
          {error && (
            <p className="mt-1 text-sm text-red-500">
              {t(error.message, error.values)}
            </p>
          )}
        </div>

        {/* 执行方法 */}
        <Button
          onClick={handleExecute}
          disabled={isExecuteDisabled}
          className="w-full"
        >
          {isDeMethodExecuting ? (
            <>
              <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              {t("Executing...")}
            </>
          ) : (
            t("Execute Method")
          )}
        </Button>

        {/* 显示结果 */}
        <div className="space-y-1">
          <div className="flex items-center justify-between">
            <label className="text-sm font-medium">
              {t("Result")}
              <span className="text-xs text-muted-foreground">
                {lastMethodCode ? `(${lastMethodCode})` : ""}
              </span>
            </label>
            {isMethodExecuting && (
              <div className="flex items-center text-sm text-muted-foreground">
                <Loader2 className="mr-2 h-3 w-3 animate-spin" />
                {t("Processing...")}
              </div>
            )}
          </div>
          <div className="group relative">
            <Textarea
              value={
                lastMethodResult
                  ? JSON.stringify(lastMethodResult, null, 2)
                  : ""
              }
              readOnly
              placeholder={t("Method execution result will appear here")}
              className="h-48 font-mono text-sm"
            />
            <Button
              size="sm"
              variant="outline"
              aria-label={t("Copy result")}
              onClick={() => {
                if (!lastMethodResult) return;
                copyToClipboard(JSON.stringify(lastMethodResult, null, 2));
              }}
              className="absolute right-2 top-2 opacity-0 group-hover:opacity-100"
            >
              <Copy className="h-2 w-2" />
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
