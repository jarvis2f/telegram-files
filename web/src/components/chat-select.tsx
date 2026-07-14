import { Archive, Check, ChevronsUpDown, Ellipsis, List } from "lucide-react";
import { Button } from "./ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "./ui/popover";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "./ui/command";
import { useEffect, useState } from "react";
import { useTelegramChat } from "@/hooks/use-telegram-chat";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { cn } from "@/lib/utils";
import { CommandLoading } from "cmdk";
import { Toggle } from "./ui/toggle";
import { TooltipWrapper } from "@/components/ui/tooltip";
import useIsMobile from "@/hooks/use-is-mobile";

export default function ChatSelect({ disabled }: { disabled: boolean }) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [archived, setArchived] = useState(false);
  const isMobile = useIsMobile();
  const {
    isLoading,
    handleQueryChange,
    chats,
    chat: selectedChat,
    handleChatChange,
    handleArchivedChange,
  } = useTelegramChat();

  const selectedChatName =
    selectedChat && (selectedChat.name || selectedChat.id);

  useEffect(() => {
    handleQueryChange(search);
  }, [search, handleQueryChange]);

  useEffect(() => {
    handleArchivedChange(archived);
  }, [archived, handleArchivedChange]);

  const trigger = (
    <Button
      variant="outline"
      role="combobox"
      aria-expanded={open}
      disabled={disabled}
      className="h-10 w-full justify-between gap-3 px-3 md:w-[250px]"
      onClick={isMobile ? () => setOpen((value) => !value) : undefined}
    >
      {selectedChat ? (
        <div className="flex min-w-0 items-center gap-2">
          <Avatar className="size-6">
            <AvatarImage src={`data:image/png;base64,${selectedChat.avatar}`} />
            <AvatarFallback>
              {selectedChat.name?.[0] ?? selectedChat.id[0]}
            </AvatarFallback>
          </Avatar>
          <span className="min-w-0 truncate">{selectedChatName}</span>
        </div>
      ) : (
        <span className="text-muted-foreground">Select chat ...</span>
      )}
      <ChevronsUpDown className="opacity-50" />
    </Button>
  );

  const chatCommand = (
    <Command shouldFilter={false} className="rounded-none">
      <div className="flex w-full border-b bg-muted/30">
        <TooltipWrapper
          content={archived ? "Show main chats" : "Show archived chats"}
        >
          <Toggle
            className="h-10 shrink-0 rounded-none border-r px-3"
            pressed={archived}
            onPressedChange={setArchived}
            aria-label={archived ? "Show main chats" : "Show archived chats"}
          >
            {archived ? <Archive /> : <List />}
          </Toggle>
        </TooltipWrapper>
        <div className="min-w-0 flex-1">
          <CommandInput
            placeholder="Search chat..."
            className="h-10"
            value={search}
            onValueChange={setSearch}
            autoFocus={!isMobile}
          />
        </div>
      </div>
      <CommandList className="relative max-h-72 touch-pan-y overscroll-contain md:max-h-[min(22rem,calc(100vh-12rem))]">
        {isLoading && (
          <CommandLoading>
            <div className="absolute inset-x-0 top-2 flex justify-center">
              <div className="rounded-full bg-background px-2 py-1 shadow-sm">
                <Ellipsis className="animate-pulse" />
              </div>
            </div>
          </CommandLoading>
        )}
        <CommandEmpty>
          {!isLoading && chats.length === 0 && "No chat found."}
        </CommandEmpty>
        <CommandGroup>
          {chats.map((chat) => (
            <CommandItem
              key={chat.id}
              value={chat.id}
              onSelect={(currentValue) => {
                handleChatChange(currentValue);
                setOpen(false);
              }}
              className="px-2 py-2"
            >
              <div className="flex min-w-0 items-center gap-2">
                <div
                  className={cn(
                    "h-6 w-1 shrink-0 rounded-full bg-primary opacity-0",
                    {
                      "opacity-100":
                        chat.auto?.download.enabled ||
                        chat.auto?.preload.enabled ||
                        chat.auto?.transfer.enabled,
                    },
                  )}
                />
                <Avatar className="size-7 shrink-0">
                  <AvatarImage src={`data:image/png;base64,${chat.avatar}`} />
                  <AvatarFallback>
                    {chat.name?.[0] ?? chat.id[0]}
                  </AvatarFallback>
                </Avatar>
                <div className="flex min-w-0 flex-col">
                  <span className="truncate font-medium">
                    {(chat.name ?? "").length > 0 ? chat.name : chat.id}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {chat.type} • {chat.unreadCount ?? 0} unread
                  </span>
                </div>
              </div>
              <Check
                className={cn(
                  "ml-auto shrink-0",
                  selectedChat?.id === chat.id ? "opacity-100" : "opacity-0",
                )}
              />
            </CommandItem>
          ))}
        </CommandGroup>
      </CommandList>
    </Command>
  );

  if (isMobile) {
    return (
      <div className="w-full">
        {trigger}
        {open && (
          <div className="mt-2 w-full overflow-hidden rounded-md border bg-popover text-popover-foreground shadow-md">
            {chatCommand}
          </div>
        )}
      </div>
    );
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>{trigger}</PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={8}
        collisionPadding={12}
        className="w-[var(--radix-popover-trigger-width)] min-w-0 overflow-hidden p-0 sm:min-w-[300px]"
      >
        {chatCommand}
      </PopoverContent>
    </Popover>
  );
}
