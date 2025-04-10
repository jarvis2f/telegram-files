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

const WS_URL = `${getWsUrl()}`;

interface WebsocketContextType {
  sendMessage: (message: WebSocketMessage) => void;
  lastJsonMessage: WebSocketMessage | null;
  connectionStatus: string;
  isReady: boolean;
  accountDownloadSpeed: number;
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
  const searchParams = useSearchParams();
  const [isReady, setIsReady] = useState(false);
  const [accountDownloadSpeed, setAccountDownloadSpeed] = useState({
    speed: 0,
    lastDownloadedSize: 0,
    lastTimestamp: 0,
  });
  const { toast } = useToast();
  const [debounceSpeed] = useDebounce(accountDownloadSpeed.speed, 300, {
    leading: true,
    maxWait: 1000,
  });

  const { sendMessage, lastJsonMessage, readyState } =
    useWebSocket<WebSocketMessage>(
      `${WS_URL}?telegramId=${searchParams.get("id") ?? ""}`,
      {
        shouldReconnect: (closeEvent) => true,
        reconnectAttempts: 3,
        reconnectInterval: 3000,
      },
    );

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
  }, [lastJsonMessage, toast]);

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
