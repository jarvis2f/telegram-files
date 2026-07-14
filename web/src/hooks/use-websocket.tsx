"use client";

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import useWebSocket, { ReadyState } from "react-use-websocket";
import {
  type TelegramError,
  type WebSocketMessage,
  WebSocketMessageType,
} from "@/lib/websocket-types";
import { useToast } from "./use-toast";
import { useDebounce } from "use-debounce";
import { getWsUrl } from "@/lib/api";
import { useSearchParams } from "next/navigation";
import { useSWRConfig } from "swr";

interface WebsocketContextType {
  sendMessage: (message: WebSocketMessage) => void;
  lastJsonMessage: WebSocketMessage | null;
  connectionStatus: string;
  isReady: boolean;
  accountDownloadSpeed: number;
  reconnect: () => void;
  telegramConnectionState: string | null;
}

const WebSocketContext = createContext<WebsocketContextType | undefined>(
  undefined,
);

interface WebSocketProviderProps {
  children: ReactNode;
}

export const WebSocketProvider: React.FC<WebSocketProviderProps> = ({
  children,
}) => {
  const wsUrl = getWsUrl();
  const searchParams = useSearchParams();
  const [isReady, setIsReady] = useState(false);
  const [accountDownloadSpeed, setAccountDownloadSpeed] = useState({
    speed: 0,
    lastDownloadedSize: 0,
    lastTimestamp: 0,
  });
  const { toast } = useToast();
  const { mutate } = useSWRConfig();
  const [debounceSpeed] = useDebounce(accountDownloadSpeed.speed, 300, {
    leading: true,
    maxWait: 1000,
  });

  const [reconnectNonce, setReconnectNonce] = useState(0);
  const [telegramConnectionState, setTelegramConnectionState] = useState<
    string | null
  >(null);

  const { sendMessage, lastJsonMessage, readyState, getWebSocket } =
    useWebSocket<WebSocketMessage>(
      `${wsUrl}?telegramId=${searchParams.get("id") ?? ""}&_r=${reconnectNonce}`,
      {
        // Keep retrying (essentially) forever with exponential backoff capped at 30s, and also
        // retry on error events — so a transient outage recovers on its own instead of giving up.
        shouldReconnect: () => true,
        reconnectAttempts: 999,
        reconnectInterval: (attemptNumber) =>
          Math.min(1000 * 2 ** attemptNumber, 30000),
        retryOnError: true,
      },
    );

  useEffect(() => {
    if (readyState !== ReadyState.CONNECTING) return;

    // A failed WebSocket handshake can leave Chromium in CONNECTING without an
    // error/close event. Rotate the URL so the hook tears down that socket and
    // retries after the API becomes available.
    const timeout = window.setTimeout(() => {
      if (getWebSocket()?.readyState === WebSocket.CONNECTING) {
        setReconnectNonce((nonce) => nonce + 1);
      }
    }, 10000);
    return () => window.clearTimeout(timeout);
  }, [getWebSocket, readyState]);

  // Force a fresh connection (resets the backoff/attempt counter); used by the manual
  // "reconnect" affordance and when the network comes back.
  const reconnect = useCallback(() => {
    setReconnectNonce((n) => n + 1);
  }, []);

  useEffect(() => {
    const maybeReconnect = () => {
      if (
        readyState !== ReadyState.OPEN &&
        readyState !== ReadyState.CONNECTING
      ) {
        setReconnectNonce((n) => n + 1);
      }
    };
    window.addEventListener("online", maybeReconnect);
    return () => {
      window.removeEventListener("online", maybeReconnect);
    };
  }, [readyState]);

  const connectionStatus = {
    [ReadyState.CONNECTING]: "Connecting",
    [ReadyState.OPEN]: "Open",
    [ReadyState.CLOSING]: "Closing",
    [ReadyState.CLOSED]: "Closed",
    [ReadyState.UNINSTANTIATED]: "Uninstantiated",
  }[readyState];

  useEffect(() => {
    setIsReady(readyState === ReadyState.OPEN);
  }, [readyState]);

  useEffect(() => {
    if (lastJsonMessage !== null) {
      // console.log(
      //   `🐄: ${JSON.stringify(lastJsonMessage, null, 2)}`,
      // )
      try {
        const payload: WebSocketMessage = lastJsonMessage;
        const timestamp = payload.timestamp;
        switch (payload.type) {
          case WebSocketMessageType.AUTHORIZATION:
            void mutate("/telegrams");
            break;
          case WebSocketMessageType.CONNECTION:
            setTelegramConnectionState(
              (payload.data as { state?: string })?.state ?? null,
            );
            break;
          case WebSocketMessageType.ERROR:
            toast({
              variant: "error",
              description: (payload.data as TelegramError).message,
            });
            break;
          case WebSocketMessageType.FILE_DOWNLOAD:
            const { downloadedSize, totalCount } = payload.data as {
              totalSize: number;
              totalCount: number;
              downloadedSize: number;
            };
            if (totalCount === 0) {
              setAccountDownloadSpeed({
                speed: 0,
                lastDownloadedSize: 0,
                lastTimestamp: 0,
              });
              return;
            }
            setAccountDownloadSpeed((prev) => {
              const state = {
                lastTimestamp: timestamp,
                lastDownloadedSize: downloadedSize,
              };
              const timeDiff = (timestamp - prev.lastTimestamp) / 1000;
              if (
                prev.lastTimestamp === 0 ||
                timeDiff <= 0 ||
                downloadedSize <= prev.lastDownloadedSize
              ) {
                return {
                  ...state,
                  speed: prev.speed,
                };
              }

              const speed =
                (downloadedSize - prev.lastDownloadedSize) / timeDiff;
              return {
                speed,
                lastDownloadedSize: downloadedSize,
                lastTimestamp: timestamp,
              };
            });
        }
      } catch (error) {
        console.error("Failed to parse WebSocket message:", error);
      }
    }
  }, [lastJsonMessage, mutate, toast]);

  const sendWebSocketMessage = useCallback(
    (message: WebSocketMessage) => {
      if (isReady) {
        sendMessage(JSON.stringify(message));
      }
    },
    [isReady, sendMessage],
  );

  return (
    <WebSocketContext.Provider
      value={{
        sendMessage: sendWebSocketMessage,
        lastJsonMessage,
        connectionStatus,
        isReady,
        accountDownloadSpeed: debounceSpeed,
        reconnect,
        telegramConnectionState,
      }}
    >
      {children}
    </WebSocketContext.Provider>
  );
};

export function useWebsocket() {
  const context = useContext(WebSocketContext);
  if (context === undefined) {
    throw new Error(
      "useTelegramWebSocket must be used within a WebSocketProvider",
    );
  }
  return context;
}
