import React, { useState } from "react";
import {
  Dialog,
  DialogContent,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Settings } from "lucide-react";
import { VisuallyHidden } from "@radix-ui/react-visually-hidden";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import FileStatistics from "@/components/file-statistics";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import Proxys from "@/components/proxys";
import SettingsForm from "@/components/settings-form";
import About from "@/components/about";
import { ChartColumnIncreasingIcon } from "@/components/ui/chart-column-increasing";
import { LayoutPanelTopIcon } from "@/components/ui/layout-panel-top";
import FilePhaseStatistics from "@/components/file-phase-statistics";
import DebugTelegramMethod from "@/components/debug-telegram-method";

export const SettingsDialog: React.FC = () => {
  const [isOpen, setIsOpen] = useState(false);
  const { account, accountId } = useTelegramAccount();

  return (
    <Dialog open={isOpen} onOpenChange={setIsOpen}>
      <DialogTrigger
        asChild
        onClick={() => {
          setIsOpen(!isOpen);
        }}
      >
        <Button variant="ghost" size="icon">
          <Settings className="h-4 w-4" />
        </Button>
      </DialogTrigger>
      <DialogContent
        className="md:max-w-2/3 h-full w-full max-w-full md:h-3/4 md:w-2/3"
        onPointerDownOutside={() => setIsOpen(false)}
        aria-describedby={undefined}
      >
        <VisuallyHidden>
          <DialogTitle>Settings</DialogTitle>
        </VisuallyHidden>
        <Tabs
          defaultValue="general"
          className="mt-3 flex h-full flex-col overflow-hidden"
        >
          <TabsList className="min-h-9 justify-start overflow-auto">
            <TabsTrigger value="general">General</TabsTrigger>
            <TabsTrigger value="statistics">Statistics</TabsTrigger>
            <TabsTrigger value="proxys">Proxys</TabsTrigger>
            <TabsTrigger value="api">API</TabsTrigger>
            <TabsTrigger value="about">About</TabsTrigger>
          </TabsList>
          <TabsContent value="general" className="overflow-hidden">
            <SettingsForm />
          </TabsContent>
          <TabsContent value="statistics" className="h-full overflow-hidden">
            <div className="no-scrollbar flex h-full flex-col overflow-y-scroll">
              {accountId ? (
                <Tabs defaultValue="panel">
                  <TabsList className="bg-none!">
                    <TabsTrigger
                      value="panel"
                      className="h-10 w-10 scale-75 data-[state=active]:bg-accent"
                    >
                      <LayoutPanelTopIcon />
                    </TabsTrigger>
                    <TabsTrigger
                      value="phase"
                      className="h-10 w-10 scale-75 data-[state=active]:bg-accent"
                    >
                      <ChartColumnIncreasingIcon />
                    </TabsTrigger>
                  </TabsList>
                  <TabsContent value="panel">
                    <FileStatistics telegramId={accountId} />
                  </TabsContent>
                  <TabsContent value="phase">
                    <FilePhaseStatistics telegramId={accountId} />
                  </TabsContent>
                </Tabs>
              ) : (
                <div className="flex flex-1 items-center justify-center">
                  <p className="text-lg text-muted-foreground">
                    Please select an account to view statistics
                  </p>
                </div>
              )}
            </div>
          </TabsContent>
          <TabsContent value="proxys" className="h-full overflow-hidden">
            <div className="no-scrollbar flex h-full flex-col overflow-y-scroll">
              <Proxys
                telegramId={accountId}
                proxyName={account?.proxy}
                enableSelect={true}
              />
            </div>
          </TabsContent>
          <TabsContent value="api" className="h-full overflow-hidden">
            <DebugTelegramMethod />
          </TabsContent>
          <TabsContent value="about" className="h-full overflow-hidden">
            <About />
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  );
};
