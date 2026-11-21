import { useCallback, useEffect, useRef, useState } from "react";
import useSWRMutation from "swr/mutation";
import type { TelegramApiResult } from "@/lib/types";
import { telegramApi, type TelegramApiArg } from "@/lib/api";
import { useWebsocket } from "@/hooks/use-websocket";
import { useI18n } from "@/hooks/use-i18n";

export function useTelegramMethod() {
  const { t } = useI18n();
  const pendingRequestsRef = useRef<
    Map<
      string,
      {
        resolve: (value: any) => void;
        reject: (reason?: any) => void;
      }
    >
  >(new Map());

  const lastResultRef = useRef<{
    code: string | null;
    result: unknown;
  }>({ code: null, result: null });

  const [pendingCount, setPendingCount] = useState(0); // 用 state 追踪 ref 的 size

  const { lastJsonMessage } = useWebsocket();

  const [lastMethod, setLastMethod] = useState<{
    code: string | null;
    result: unknown;
  }>({
    code: null,
    result: null,
  });

  useEffect(() => {
    if (!lastJsonMessage?.code) return;

    const code = lastJsonMessage.code;
    const data = lastJsonMessage.data;

    lastResultRef.current = { code, result: data };
    setLastMethod({ code, result: data }); // 同步 state

    const pendingRequest = pendingRequestsRef.current.get(code);
    if (pendingRequest) {
      pendingRequest.resolve(data);
      pendingRequestsRef.current.delete(code);
      setPendingCount(pendingRequestsRef.current.size);
    }
  }, [lastJsonMessage]);

  useEffect(() => {
    if (!lastJsonMessage?.code) return;

    const code = lastJsonMessage.code;
    const data = lastJsonMessage.data;

    lastResultRef.current = { code, result: data };

    const pendingRequest = pendingRequestsRef.current.get(code);
    if (pendingRequest) {
      pendingRequest.resolve(data);
      pendingRequestsRef.current.delete(code);
      setPendingCount(pendingRequestsRef.current.size); // 更新 state
    }
  }, [lastJsonMessage]);

  const { trigger, isMutating } = useSWRMutation<
    TelegramApiResult,
    Error,
    string,
    TelegramApiArg
  >("/telegram/api", telegramApi);

  const executeMethod = useCallback(
    async (arg: TelegramApiArg): Promise<any> => {
      try {
        const result = await trigger(arg);
        const { code } = result;

        if (lastResultRef.current.code === code) {
          return lastResultRef.current.result;
        }

        return new Promise((resolve, reject) => {
          const timeoutId = setTimeout(() => {
            pendingRequestsRef.current.delete(code);
            setPendingCount(pendingRequestsRef.current.size); // 更新 state
            reject(new Error(t("Request timeout for code: {code}", { code })));
          }, 30000);

          pendingRequestsRef.current.set(code, {
            resolve: (value) => {
              clearTimeout(timeoutId);
              pendingRequestsRef.current.delete(code);
              setPendingCount(pendingRequestsRef.current.size); // 更新 state
              resolve(value);
            },
            reject: (reason) => {
              clearTimeout(timeoutId);
              pendingRequestsRef.current.delete(code);
              setPendingCount(pendingRequestsRef.current.size); // 更新 state
              reject(
                reason instanceof Error ? reason : new Error(String(reason)),
              );
            },
          });

          setPendingCount(pendingRequestsRef.current.size); // 更新 state
        });
      } catch (error) {
        throw error;
      }
    },
    [t, trigger],
  );

  const isMethodExecuting = isMutating || pendingCount > 0;

  return {
    executeMethod,
    triggerMethod: trigger,
    isMethodExecuting,
    lastMethodCode: lastMethod.code,
    lastMethodResult: lastMethod.result,
    pendingRequestsCount: pendingCount,
  };
}
