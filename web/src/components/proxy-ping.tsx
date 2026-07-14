import useSWR from "swr";
import { ChevronsLeftRightEllipsis } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

export default function ProxyPing({ accountId }: { accountId: string }) {
  const { data, isLoading, error, mutate } = useSWR<
    {
      ping: number;
    },
    Error
  >(`/telegram/${accountId}/ping`, {
    errorRetryCount: 2,
  });

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="h-8 gap-2 px-2.5 font-mono text-xs"
            onClick={() => {
              if (isLoading) return;
              void mutate(undefined, true);
            }}
          >
            <ChevronsLeftRightEllipsis className="text-muted-foreground" />
            {isLoading && (
              <span className="h-4 w-14 animate-pulse rounded bg-muted text-transparent">
                testing
              </span>
            )}
            {!isLoading && error && (
              <span className="text-destructive">Failed</span>
            )}
            {!isLoading && data && (
              <span>{(data.ping * 1000).toFixed(0)} ms</span>
            )}
          </Button>
        </TooltipTrigger>
        <TooltipContent>Click to refresh Telegram API latency</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
