import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Ellipsis } from "lucide-react";
import { type useTelegramAccount } from "@/hooks/use-telegram-account";
import { useI18n } from "@/hooks/use-i18n";

type AccountSelectProps = ReturnType<typeof useTelegramAccount>;

export default function AccountSelect({
  isLoading,
  getAccounts,
  accountId,
  account,
  handleAccountChange,
}: AccountSelectProps) {
  const { t } = useI18n();
  const accounts = getAccounts("active");

  return (
    <Select value={accountId} onValueChange={handleAccountChange}>
      <SelectTrigger className="w-full md:w-[200px]">
        <SelectValue placeholder={t("Select account ...")}>
          {account ? (
            <div className="flex items-center gap-2">
              <Avatar className="h-6 w-6">
                <AvatarImage src={`data:image/png;base64,${account.avatar}`} />
                <AvatarFallback>{account.name[0]}</AvatarFallback>
              </Avatar>
              <span className="max-w-[170px] overflow-hidden truncate">
                {account.name}
              </span>
            </div>
          ) : (
            t("Select account ...")
          )}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {isLoading && (
          <SelectItem value="loading" disabled className="flex justify-center">
            <Ellipsis className="h-4 w-4 animate-pulse" />
          </SelectItem>
        )}
        {accounts.map((account) => (
          <SelectItem key={account.id} value={account.id}>
            <div className="flex items-center gap-2">
              <Avatar className="h-6 w-6">
                <AvatarImage src={`data:image/png;base64,${account.avatar}`} />
                <AvatarFallback>{account.name[0]}</AvatarFallback>
              </Avatar>
              <div className="flex flex-col">
                <span className="font-medium">{account.name}</span>
                <span className="text-xs text-muted-foreground">
                  {account.phoneNumber}
                </span>
              </div>
            </div>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
