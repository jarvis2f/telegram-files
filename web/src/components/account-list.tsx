import { type TelegramAccount } from "@/lib/types";
import { Card, CardContent } from "@/components/ui/card";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Circle, PhoneCall } from "lucide-react";
import { Spoiler } from "spoiled";
import AccountDeleteDialog from "@/components/account-delete-dialog";
import { AccountDialog } from "@/components/account-dialog";
import { Button } from "@/components/ui/button";

interface AccountListProps {
  accounts: TelegramAccount[];
  onSelectAccount: (accountId: string) => void;
}

export function AccountList({ accounts, onSelectAccount }: AccountListProps) {
  return (
    <div className="mx-auto grid max-w-5xl grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
      {accounts.map((account) => (
        <Card
          key={account.id}
          className="group relative cursor-pointer transition-shadow hover:shadow-lg"
          onClick={(_e) => {
            onSelectAccount(account.id);
          }}
        >
          <AccountDeleteDialog
            telegramId={account.id}
            className="absolute bottom-1 right-1 hidden group-hover:inline-flex"
          />
          <CardContent className="p-6">
            <div className="flex items-start gap-4">
              <Avatar className="h-12 w-12">
                <AvatarImage src={`data:image/jpeg;base64,${account.avatar}`} />
                <AvatarFallback>{account.name[0]}</AvatarFallback>
              </Avatar>
              <div className="flex-1">
                <div className="mb-2 flex items-center justify-between">
                  <h3 className="font-semibold">{account.name}</h3>
                  <Badge
                    variant={
                      account.status === "active" && !account.sleeping
                        ? "default"
                        : "secondary"
                    }
                  >
                    <Circle
                      className={`mr-1 h-2 w-2 ${
                        account.sleeping
                          ? "text-amber-500"
                          : account.status === "active"
                            ? "text-green-500"
                            : "text-gray-500"
                      }`}
                    />
                    {account.sleeping ? "sleeping" : account.status}
                  </Badge>
                </div>
                {account.status === "active" && !account.sleeping && (
                  <div className="mb-4 flex items-center text-sm text-muted-foreground">
                    <PhoneCall className="mr-1 h-3 w-3" />
                    <Spoiler>{account.phoneNumber}</Spoiler>
                  </div>
                )}
                {account.status === "inactive" && (
                  <AccountDialog>
                    <Button variant="outline" size="sm">
                      Activate
                    </Button>
                  </AccountDialog>
                )}
              </div>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
