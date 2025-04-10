"use client";
import React, { useState } from "react";
import { useSettings } from "@/hooks/use-settings";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Copy, Edit2, Plus, Trash2 } from "lucide-react";
import { type Proxy } from "@/lib/types";
import { cn, parseProxyString } from "@/lib/utils";
import useSWRMutation from "swr/mutation";
import { request } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import ProxyPing from "@/components/proxy-ping";
import { Label } from "./ui/label";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { mutate } from "swr";

export interface ProxysProps {
  enableSelect?: boolean;
  telegramId?: string;
  proxyName?: string;
  onProxyNameChange?: (name: string) => void;
}

export default function Proxys({
  enableSelect,
  telegramId,
  proxyName,
  onProxyNameChange,
}: ProxysProps) {
  const { settings, updateSettings } = useSettings();
  const [innerProxyName, setInnerProxyName] = useState(proxyName ?? "");
  const [isDialogOpen, setDialogOpen] = useState(false);
  const [editingProxy, setEditingProxy] = useState<Proxy | null>(null);
  const [formState, setFormState] = useState<Proxy>({
    name: "",
    server: "",
    port: 0,
    username: "",
    password: "",
    type: "http",
  });
  const { trigger: triggerProxy, isMutating: isToggleProxyMutating } =
    useSWRMutation<{ id: string }, Error>(
      telegramId ? `/telegram/${telegramId}/toggle-proxy` : undefined,
      async (key: string) => {
        // eslint-disable-next-line @typescript-eslint/no-unsafe-return
        return await request(key, {
          method: "POST",
          body: JSON.stringify({
            proxyName: innerProxyName,
          }),
        });
      },
      {
        onSuccess: () => {
          void mutate(`/telegrams`);
          toast({
            variant: "success",
            description: innerProxyName
              ? `Proxy is set to ${innerProxyName}`
              : "Proxy is disabled",
          });
        },
      },
    );

  // Parse proxy settings
  const proxys = (
    (settings?.proxys
      ? JSON.parse(settings.proxys)
      : {
          items: [],
        }) as {
      items: Proxy[];
    }
  ).items;

  // Open dialog for adding or editing proxy
  const handleOpenDialog = (proxy: Proxy | null = null): void => {
    setEditingProxy(proxy);
    setFormState(
      proxy ?? {
        name: "",
        server: "",
        port: 0,
        username: "",
        password: "",
        type: "http",
      },
    );
    setDialogOpen(true);
  };

  // Close dialog
  const handleCloseDialog = (): void => {
    setDialogOpen(false);
    setEditingProxy(null);
  };

  // Delete proxy with confirmation
  const handleDeleteProxy = (proxy: Proxy): void => {
    if (confirm(`Are you sure you want to delete the proxy ${proxy.name}?`)) {
      const updatedProxys = proxys.filter((p) => p.name !== proxy.name);
      void updateSettings({
        proxys: JSON.stringify({
          items: updatedProxys,
        }),
      });
    }
  };

  // Save proxy (either add or edit)
  const handleSaveProxy = (): void => {
    const updatedProxys = editingProxy
      ? proxys.map((p) => (p.name === editingProxy.name ? formState : p))
      : [...proxys, formState];
    void updateSettings({ proxys: JSON.stringify({ items: updatedProxys }) }); // Submit to server
    handleCloseDialog();
  };

  // Handle form input change
  const handleInputChange = (
    e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>,
  ): void => {
    const { name, value } = e.target;
    setFormState((prev) => ({
      ...prev,
      [name]: name === "port" ? parseInt(value, 10) : value,
    }));
  };

  const handleProxySubmit = async () => {
    if (telegramId) {
      await triggerProxy();
    } else {
      onProxyNameChange?.(innerProxyName);
    }
  };

  return (
    <div className="relative h-full">
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <h1 className="text-2xl font-bold">Proxys</h1>
          {telegramId && <ProxyPing accountId={telegramId} />}
        </div>
        <Button onClick={() => handleOpenDialog()}>
          <Plus className="mr-2 h-5 w-5" /> Add Proxy
        </Button>
      </div>
      {proxys.length === 0 && (
        <div className="flex h-32 items-center justify-center">
          <p className="text-center text-gray-500">No proxys added yet</p>
        </div>
      )}
      <div className="grid grid-cols-1 gap-6 md:grid-cols-3 2xl:grid-cols-4">
        {proxys.map((proxy) => (
          <Card
            key={proxy.name}
            className={cn("relative hover:shadow-lg", {
              "cursor-pointer": enableSelect,
            })}
            onClick={() => {
              if (innerProxyName === proxy.name) {
                setInnerProxyName("");
              } else {
                setInnerProxyName(proxy.name);
              }
            }}
          >
            <CardHeader className="p-3">
              <CardTitle className="flex items-center justify-between">
                <span className="font-semibold">{proxy.name}</span>
                <Checkbox
                  className="h-4 w-4"
                  checked={enableSelect && innerProxyName === proxy.name}
                />
              </CardTitle>
            </CardHeader>
            <CardContent className="p-0 px-3">
              <div className="flex items-baseline space-x-2">
                <Badge variant="outline">{proxy.type.toUpperCase()}</Badge>
                <p className="text-sm text-gray-400">{`${proxy.server}:${proxy.port}`}</p>
              </div>
            </CardContent>
            <CardFooter className="flex justify-end space-x-2 p-1">
              <Button
                size="icon"
                variant="ghost"
                className="h-6 w-6"
                onClick={() => handleOpenDialog(proxy)}
              >
                <Edit2 className="h-3 w-3" />
              </Button>
              <Button
                size="icon"
                variant="ghost"
                className="h-6 w-6"
                onClick={() => handleDeleteProxy(proxy)}
              >
                <Trash2 className="h-3 w-3 text-red-600" />
              </Button>
            </CardFooter>
          </Card>
        ))}
      </div>
      {enableSelect && (
        <div className="absolute bottom-0 flex w-full flex-col-reverse items-center justify-end gap-2 sm:flex-row">
          <DialogClose asChild>
            <Button
              className="w-full md:w-auto"
              variant="outline"
              type="button"
            >
              Cancel
            </Button>
          </DialogClose>
          <Button
            className="w-full md:w-auto"
            disabled={isToggleProxyMutating}
            onClick={() => handleProxySubmit()}
          >
            Submit
          </Button>
        </div>
      )}
      <Dialog open={isDialogOpen} onOpenChange={handleCloseDialog}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              <span className="mr-2">
                {editingProxy ? "Edit Proxy" : "Add Proxy"}
              </span>
              <ProxyParser onParsed={(proxy) => setFormState(proxy)} />
            </DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            {/* Radio buttons for proxy type */}
            <div>
              <Label className="mb-2 block">Type</Label>
              <div className="flex space-x-4">
                <label className="flex cursor-pointer items-center">
                  <input
                    type="radio"
                    name="type"
                    value="http"
                    checked={formState.type === "http"}
                    onChange={handleInputChange}
                    className="peer hidden"
                  />
                  <div className="mr-2 flex h-5 w-5 items-center justify-center rounded-full border-2 border-gray-300 peer-checked:border-primary peer-checked:bg-primary">
                    <div className="h-2.5 w-2.5 rounded-full bg-white"></div>
                  </div>
                  <span className="text-gray-400 peer-checked:text-primary">
                    HTTP
                  </span>
                </label>

                <label className="flex cursor-pointer items-center">
                  <input
                    type="radio"
                    name="type"
                    value="socks5"
                    checked={formState.type === "socks5"}
                    onChange={handleInputChange}
                    className="peer hidden"
                  />
                  <div className="mr-2 flex h-5 w-5 items-center justify-center rounded-full border-2 border-gray-300 peer-checked:border-primary peer-checked:bg-primary">
                    <div className="h-2.5 w-2.5 rounded-full bg-white"></div>
                  </div>
                  <span className="text-gray-400 peer-checked:text-primary">
                    SOCKS5
                  </span>
                </label>
              </div>
            </div>

            <div>
              <Label className="mb-2 block">Name</Label>
              <Input
                name="name"
                value={formState.name}
                onChange={handleInputChange}
                placeholder="Enter proxy name"
              />
            </div>
            <div>
              <Label className="mb-2 block">
                Proxy server address and port number
              </Label>
              <div className="flex space-x-4">
                <div className="flex-1">
                  <label className="block text-xs font-medium text-gray-500">
                    Server
                  </label>
                  <Input
                    name="server"
                    value={formState.server}
                    onChange={handleInputChange}
                    placeholder="Enter server address"
                  />
                </div>
                <div className="w-24">
                  <label className="block text-xs font-medium text-gray-500">
                    Port
                  </label>
                  <Input
                    name="port"
                    type="number"
                    value={formState.port}
                    onChange={handleInputChange}
                    placeholder="Enter port number"
                  />
                </div>
              </div>
            </div>
            <Label className="mb-2 block">Authentication (optional)</Label>
            <div>
              <label className="block text-xs font-medium text-gray-500">
                Username
              </label>
              <Input
                name="username"
                value={formState.username}
                onChange={handleInputChange}
                placeholder="Enter username"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-500">
                Password
              </label>
              <Input
                name="password"
                type="password"
                value={formState.password}
                onChange={handleInputChange}
                placeholder="Enter password"
              />
            </div>
          </div>
          <DialogFooter>
            <Button onClick={handleCloseDialog} variant="outline">
              Cancel
            </Button>
            <Button onClick={handleSaveProxy}>
              {editingProxy ? "Save" : "Add"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ProxyParser({ onParsed }: { onParsed: (proxys: Proxy) => void }) {
  const handleParseProxy = async () => {
    const clipboardText = await navigator.clipboard.readText();
    if (!clipboardText || clipboardText.trim().length === 0) {
      return;
    }
    const proxy = parseProxyString(clipboardText);
    if (proxy) {
      toast({
        variant: "success",
        description: "Proxy string is parsed successfully",
      });
      onParsed(proxy);
    } else {
      toast({
        variant: "error",
        description: "Invalid proxy string format",
      });
    }
  };

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button size="xs" variant="ghost" onClick={() => handleParseProxy()}>
            <Copy className="h-3 w-3" />
          </Button>
        </TooltipTrigger>
        <TooltipContent>
          <div className="flex flex-col space-y-2 p-2">
            <p className="text-sm font-semibold">
              Parse the proxy string from the clipboard and add them to the list
            </p>
            <p className="text-xs text-gray-500">
              The proxy should be in the following format:
              <br />
              <code>http://username:password@server:port</code>
              <br />
              <code>socks://username:password@server:port</code>
            </p>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
