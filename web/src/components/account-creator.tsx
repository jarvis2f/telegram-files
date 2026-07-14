import { useTelegramMethod } from "@/hooks/use-telegram-method";
import useSWRMutation from "swr/mutation";
import { request } from "@/lib/api";
import { useToast } from "@/hooks/use-toast";
import { useSWRConfig } from "swr";
import React, {
  type FormEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { useDebounce } from "use-debounce";
import {
  AlertTriangle,
  Ellipsis,
  LoaderCircle,
  LockKeyhole,
  MessageSquareCode,
  Phone,
  QrCode,
  Rocket,
  ShieldAlert,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  TelegramConstructor,
  type TelegramObject,
  WebSocketMessageType,
} from "@/lib/websocket-types";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import {
  InputOTP,
  InputOTPGroup,
  InputOTPSlot,
} from "@/components/ui/input-otp";
import { useWebsocket } from "@/hooks/use-websocket";
import { useTelegramAccount } from "@/hooks/use-telegram-account";
import TGDuck16Plane from "@/components/animations/tg-duck16_plane.json";
import TGQRPlane from "@/components/animations/tg-qr-plane.json";
import dynamic from "next/dynamic";
import QRCodeStyling, { type Options } from "qr-code-styling";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";

function AuthPanel({
  icon: Icon,
  title,
  description,
  children,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-md border bg-card p-4 shadow-sm">
      <div className="mb-4 flex items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-md border bg-muted text-muted-foreground">
          <Icon className="size-5" />
        </span>
        <div className="min-w-0">
          <h3 className="font-semibold">{title}</h3>
          <p className="mt-1 text-sm text-muted-foreground">{description}</p>
        </div>
      </div>
      {children}
    </div>
  );
}

function InlineNotice({
  tone = "default",
  title,
  children,
}: {
  tone?: "default" | "warning" | "destructive";
  title?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className={cn(
        "rounded-md border p-3 text-sm",
        tone === "warning" && "border-amber-500/30 bg-amber-500/10",
        tone === "destructive" && "border-destructive/30 bg-destructive/10",
        tone === "default" && "bg-muted/40 text-muted-foreground",
      )}
    >
      {title && <p className="mb-1 font-medium text-foreground">{title}</p>}
      <div className="text-muted-foreground">{children}</div>
    </div>
  );
}

interface AccountCreatorProps {
  isAdd?: boolean;
  proxyName: string | undefined;
  onCreated?: (id: string) => void;
  onLoginSuccess?: () => void;
}

const Lottie = dynamic(() => import("lottie-react"));

export default function AccountCreator({
  isAdd,
  proxyName,
  onCreated,
  onLoginSuccess,
}: AccountCreatorProps) {
  const { triggerMethod, isMethodExecuting } = useTelegramMethod();
  const { toast } = useToast();
  const { mutate } = useSWRConfig();
  const { lastJsonMessage } = useWebsocket();
  const { account, resetAccount } = useTelegramAccount();
  const [initSuccessfully, setInitSuccessfully] = useState(false);
  const [authState, setAuthState] = useState<number | undefined>(undefined);
  const [qrCodeLink, setQrCodeLink] = useState<string | undefined>(undefined);
  const [phoneNumber, setPhoneNumber] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [isDeMethodExecuting] = useDebounce(isMethodExecuting, 500, {
    leading: true,
  });
  const {
    trigger: triggerCreate,
    isMutating: isCreateMutating,
    error: createError,
  } = useSWRMutation<{ id: string }, Error>(
    "/telegram/create",
    async (key: string) => {
      return await request(key, {
        method: "POST",
        body: JSON.stringify({
          proxyName: proxyName,
        }),
      });
    },
    {
      onSuccess: (data) => {
        onCreated?.(data.id);
      },
    },
  );
  const [debounceIsCreateMutating] = useDebounce(isCreateMutating, 1000, {
    leading: true,
  });

  const handleAuthState = useCallback(
    (state: TelegramObject) => {
      switch (state.constructor) {
        case TelegramConstructor.WAIT_PHONE_NUMBER:
        case TelegramConstructor.WAIT_CODE:
        case TelegramConstructor.WAIT_PASSWORD:
        case TelegramConstructor.WAIT_PREMIUM_PURCHASE:
          setAuthState(state.constructor);
          break;
        case TelegramConstructor.WAIT_OTHER_DEVICE_CONFIRMATION:
          setAuthState(state.constructor);
          setQrCodeLink(state.link as string);
          break;
        case TelegramConstructor.STATE_READY:
          toast({
            variant: "success",
            description: "Account added successfully",
          });
          setTimeout(() => {
            void mutate("/telegrams");
            onLoginSuccess?.();
            setPhoneNumber("");
            setCode("");
            setPassword("");
          }, 1000);
          break;
        default:
          setTimeout(() => {
            void mutate("/telegrams");
          }, 500);
          console.log("Unknown telegram constructor:", state.constructor);
      }
    },
    [mutate, onLoginSuccess, toast],
  );

  useEffect(() => {
    if (account) {
      if (
        !isAdd &&
        account.status === "inactive" &&
        account.lastAuthorizationState
      ) {
        setInitSuccessfully(true);
        handleAuthState(account.lastAuthorizationState);
        return;
      }
    }

    if (isAdd && !initSuccessfully) {
      resetAccount();
    }
  }, [account, handleAuthState, initSuccessfully, isAdd, resetAccount]);

  useEffect(() => {
    if (!lastJsonMessage) return;

    if (lastJsonMessage.type === WebSocketMessageType.AUTHORIZATION) {
      handleAuthState(lastJsonMessage.data as TelegramObject);
    }
  }, [handleAuthState, lastJsonMessage]);

  useEffect(() => {
    if (phoneNumber) {
      setPhoneNumber((prev) => prev.replaceAll(/\D/g, ""));
    }
  }, [phoneNumber]);

  if (debounceIsCreateMutating) {
    return (
      <div className="rounded-md border bg-card p-4 shadow-sm">
        <div className="mb-4 flex items-center gap-3">
          <Skeleton className="size-10 rounded-md" />
          <div className="flex flex-1 flex-col gap-2">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-3 w-64 max-w-full" />
          </div>
        </div>
        <div className="flex flex-col gap-2">
          <Skeleton className="h-9 w-full" />
          <Skeleton className="h-9 w-full" />
        </div>
      </div>
    );
  }

  if (createError) {
    return (
      <div className="rounded-md border border-destructive/40 bg-card p-4 text-destructive shadow-sm">
        <div className="flex items-start gap-3">
          <AlertTriangle className="mt-0.5 size-5 shrink-0" />
          <div>
            <p className="font-medium">Initializing account failed</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Please try again later, or choose another proxy before retrying.
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (!initSuccessfully) {
    return (
      <div className="rounded-md border bg-card p-5 shadow-sm">
        <div className="flex flex-col items-center gap-4 text-center">
          <div className="relative">
            <div className="absolute inset-3 rounded-full bg-muted" />
            <Lottie
              className="relative size-28"
              animationData={TGDuck16Plane}
              loop={true}
            />
          </div>
          <div>
            <div className="flex items-center justify-center gap-2">
              <h3 className="text-lg font-semibold">Initialize Telegram</h3>
              {proxyName && (
                <Badge variant="secondary">Proxy: {proxyName}</Badge>
              )}
            </div>
            <p className="mt-1 text-sm text-muted-foreground">
              Start a secure Telegram authorization session for this account.
            </p>
          </div>
          <Button
            className="w-full"
            disabled={debounceIsCreateMutating}
            onClick={async () => {
              await triggerCreate().then(() => {
                void mutate("/telegrams");
                setInitSuccessfully(true);
              });
            }}
          >
            <Rocket />
            Start initialization
          </Button>
        </div>
      </div>
    );
  }

  if (!authState && !isMethodExecuting) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 rounded-md border bg-muted/30 p-5 text-center">
        <Ellipsis className="size-5 animate-pulse text-muted-foreground" />
        <div>
          <p className="font-medium">Waiting for Telegram authorization</p>
          <p className="mt-1 text-sm text-muted-foreground">
            If this takes too long, refresh the page or try again later.
          </p>
        </div>
      </div>
    );
  }

  const authStateFormFields = {
    [TelegramConstructor.WAIT_PHONE_NUMBER]: (
      <AuthPanel
        icon={Phone}
        title="Phone number"
        description="Enter the account phone number with country code."
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="phone">Phone Number</Label>
          <Input
            id="phone"
            placeholder="8613712345678"
            value={phoneNumber}
            onChange={(e) => setPhoneNumber(e.target.value)}
            disabled={isMethodExecuting}
            required
          />
          <p className="text-sm text-muted-foreground">
            Example: 8613712345678. Non-numeric characters are removed
            automatically.
          </p>
        </div>
      </AuthPanel>
    ),
    [TelegramConstructor.WAIT_OTHER_DEVICE_CONFIRMATION]: (
      <AuthPanel
        icon={QrCode}
        title="QR code login"
        description="Scan with an existing Telegram app session."
      >
        <QRCodePanel link={qrCodeLink} />
      </AuthPanel>
    ),
    [TelegramConstructor.WAIT_CODE]: (
      <AuthPanel
        icon={MessageSquareCode}
        title="Authentication code"
        description="Enter the code sent to your Telegram account."
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="code">Authentication Code</Label>
          <InputOTP
            id="code"
            maxLength={6}
            value={code}
            disabled={isMethodExecuting}
            required
            onChange={(value) => setCode(value)}
          >
            <InputOTPGroup>
              <InputOTPSlot index={0} />
              <InputOTPSlot index={1} />
              <InputOTPSlot index={2} />
              <InputOTPSlot index={3} />
              <InputOTPSlot index={4} />
              <InputOTPSlot index={5} />
            </InputOTPGroup>
          </InputOTP>
        </div>
      </AuthPanel>
    ),
    [TelegramConstructor.WAIT_PASSWORD]: (
      <AuthPanel
        icon={LockKeyhole}
        title="Two-step verification"
        description="Enter the password configured for this Telegram account."
      >
        <div className="flex flex-col gap-2">
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={isMethodExecuting}
            required
          />
          <InlineNotice tone="warning">
            Using passkeys instead of a password? Close this dialog, start over,
            and use <strong>Log in by QR code</strong> from an existing Telegram
            device.
          </InlineNotice>
        </div>
      </AuthPanel>
    ),
    [TelegramConstructor.WAIT_PREMIUM_PURCHASE]: (
      <AuthPanel
        icon={ShieldAlert}
        title="Telegram Premium required"
        description="This account requires an active Telegram Premium subscription to log in."
      >
        <InlineNotice tone="destructive">
          Purchase Telegram Premium in the official Telegram app and try again.
        </InlineNotice>
      </AuthPanel>
    ),
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (authState === TelegramConstructor.WAIT_PHONE_NUMBER) {
      await triggerMethod({
        data: {
          phoneNumber: phoneNumber,
          settings: null,
        },
        method: "SetAuthenticationPhoneNumber",
      });
    } else if (authState === TelegramConstructor.WAIT_CODE) {
      await triggerMethod({
        data: {
          code: code,
        },
        method: "CheckAuthenticationCode",
      });
    } else if (authState === TelegramConstructor.WAIT_PASSWORD) {
      await triggerMethod({
        data: {
          password: password,
        },
        method: "CheckAuthenticationPassword",
      });
    }
  };

  const handleRequestQrCodeAuthentication = async () => {
    await triggerMethod({
      data: {
        otherUserIds: null,
      },
      method: "RequestQrCodeAuthentication",
    });
  };

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      {authState && (
        <>
          {authStateFormFields[authState]}
          {authState !== TelegramConstructor.WAIT_OTHER_DEVICE_CONFIRMATION &&
            authState !== TelegramConstructor.WAIT_PREMIUM_PURCHASE && (
              <Button
                type="submit"
                className="w-full"
                disabled={isMethodExecuting}
              >
                {isDeMethodExecuting ? (
                  <LoaderCircle className="animate-spin" />
                ) : (
                  "Submit"
                )}
              </Button>
            )}
          {authState === TelegramConstructor.WAIT_PHONE_NUMBER && (
            <Button
              variant="outline"
              className="w-full"
              disabled={isMethodExecuting}
              onClick={handleRequestQrCodeAuthentication}
            >
              {isDeMethodExecuting ? (
                <LoaderCircle className="animate-spin" />
              ) : (
                "Log in by QR code"
              )}
            </Button>
          )}
        </>
      )}
    </form>
  );
}

const options: Options = {
  width: 280,
  height: 280,
  type: "svg",
  image: "blank.png",
  margin: 10,
  qrOptions: {
    errorCorrectionLevel: "M",
  },
  cornersSquareOptions: {
    type: "extra-rounded",
  },
  imageOptions: {
    imageSize: 0.4,
    margin: 8,
  },
  dotsOptions: {
    type: "rounded",
  },
};

function QRCodePanel({ link }: { link?: string }) {
  const [qrCode, setQrCode] = useState<QRCodeStyling>();
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (ref.current) {
      qrCode?.append(ref.current);
    }
  }, [qrCode, ref]);

  useEffect(() => {
    if (link) {
      if (!qrCode) {
        const qrCode = new QRCodeStyling({
          ...options,
          data: link,
        });
        setQrCode(qrCode);
      } else {
        qrCode.update({
          data: link,
        });
      }
    }
  }, [link, qrCode]);

  if (!link) {
    return (
      <div className="flex min-h-72 items-center justify-center">
        <LoaderCircle className="size-10 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center justify-center gap-3">
      <div className="relative flex items-center justify-center rounded-[2rem] border bg-white p-2 shadow-sm">
        <div className="overflow-hidden rounded-3xl bg-white" ref={ref} />
        <Lottie
          className="absolute left-1/2 top-1/2 z-10 size-14 -translate-x-1/2 -translate-y-1/2 transform rounded-full bg-foreground"
          animationData={TGQRPlane}
          loop={true}
        />
      </div>
      <p className="max-w-72 text-center text-sm text-muted-foreground">
        Open Telegram on another device, go to linked devices, and scan this QR
        code.
      </p>
    </div>
  );
}
