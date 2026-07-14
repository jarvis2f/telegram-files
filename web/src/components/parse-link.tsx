import { Button } from "@/components/ui/button";
import { WandSparkles } from "lucide-react";
import { TooltipWrapper } from "@/components/ui/tooltip";
import { toast } from "@/hooks/use-toast";
import React, { useState } from "react";
import {
  Drawer,
  DrawerClose,
  DrawerContent,
  DrawerFooter,
  DrawerTitle,
} from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useRouter } from "next/navigation";
import useIsMobile from "@/hooks/use-is-mobile";

interface ParseLinkButtonProps {
  accountId: string;
}

export default function ParseLinkButton({ accountId }: ParseLinkButtonProps) {
  const isMobile = useIsMobile();
  const router = useRouter();
  const [open, setOpen] = useState(false);

  function parseLink(text: string) {
    if (text) {
      // Check if the text is a valid Telegram link
      const telegramLinkRegex = /https?:\/\/t\.me\/([a-zA-Z0-9_]+)/;
      const match = telegramLinkRegex.exec(text);
      if (!match) {
        toast({
          title: "Invalid link",
          description:
            "Please copy a valid Telegram link. Example: https://t.me/username",
          variant: "error",
        });
        return;
      }
      setOpen(false); // Close the drawer if it was open
      toast({
        title: "Parsing link...",
        description: `Parsing link: ${text}`,
        variant: "success",
      });
      // Redirect to the account page with the parsed link
      router.push(
        `/accounts?id=${accountId}&link=${window.encodeURIComponent(text)}`,
      );
    } else {
      toast({
        title: "No link found in clipboard",
        description: "Please copy a Telegram link to parse.",
        variant: "info",
      });
    }
  }

  function handleParseLink() {
    if (!navigator.clipboard?.readText) {
      // If clipboard API is not supported, show manual input
      setOpen(true);
      return;
    }
    // Get the link from clipboard
    navigator.clipboard
      .readText()
      .then((text) => parseLink(text))
      .catch((err) => {
        console.info("Failed to read clipboard contents: ", err);
        setOpen(true); // Fallback to manual input if clipboard read fails
      });
  }

  return (
    <>
      {isMobile ? (
        <MobileParseLinkDrawer
          open={open}
          onOpenChange={setOpen}
          toggleParseLink={parseLink}
        />
      ) : (
        <ParseLinkDialog
          open={open}
          onOpenChange={setOpen}
          toggleParseLink={parseLink}
        />
      )}
      <TooltipWrapper content="You can parse links from Telegram chats to get files directly.">
        <Button className="mt-4" onClick={handleParseLink}>
          <WandSparkles className="mr-2 h-4 w-4" />
          Parse Link
        </Button>
      </TooltipWrapper>
    </>
  );
}

function ParseLinkDialog({
  open,
  onOpenChange,
  toggleParseLink,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  toggleParseLink: (link: string) => void;
}) {
  const [link, setLink] = useState<string>("");

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="w-full md:w-2/3">
        <DialogHeader>
          <DialogTitle>Parse Link</DialogTitle>
          <DialogDescription>
            Enter a Telegram link to parse files from it. Example:{" "}
            <code>https://t.me/username</code>
          </DialogDescription>
        </DialogHeader>

        <Input
          placeholder="Enter Telegram link"
          value={link}
          onChange={(e) => setLink(e.target.value)}
          className="mb-4"
          autoFocus
        />

        <DialogFooter>
          <Button
            disabled={link.trim() === ""}
            onClick={() => {
              toggleParseLink(link);
            }}
          >
            Submit
          </Button>
          <DialogClose asChild>
            <Button variant="outline">Close</Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function MobileParseLinkDrawer({
  open,
  onOpenChange,
  toggleParseLink,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  toggleParseLink: (link: string) => void;
}) {
  const [link, setLink] = useState<string>("");
  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      disablePreventScroll={true}
      repositionInputs={false}
    >
      <DrawerContent className="w-full md:w-2/3" aria-describedby={undefined}>
        <div className="p-4">
          <DrawerTitle className="mb-4">Parse Link</DrawerTitle>
          <p className="mb-2 text-sm text-muted-foreground">
            Enter a Telegram link to parse files from it. Example:{" "}
            <code>https://t.me/username</code>
          </p>
          <Input
            placeholder="Enter Telegram link"
            value={link}
            onChange={(e) => setLink(e.target.value)}
            className="mb-4"
            autoFocus
          />
        </div>

        <DrawerFooter>
          <Button
            disabled={link.trim() === ""}
            onClick={() => {
              toggleParseLink(link);
            }}
          >
            Submit
          </Button>
          <DrawerClose asChild>
            <Button variant="outline">Close</Button>
          </DrawerClose>
        </DrawerFooter>
      </DrawerContent>
    </Drawer>
  );
}
