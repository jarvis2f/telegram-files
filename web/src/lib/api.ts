import { env } from "@/env";
import { browserWriteHeaders, isSessionTerminal } from "@/lib/security";

export const SESSION_TERMINAL_EVENT = "telegram-files:session-terminal";

const CSRF_EXEMPT_PATHS = new Set(["/auth/bootstrap", "/auth/login"]);

export function getApiUrl(): string {
  const url = env.NEXT_PUBLIC_API_URL;
  if (url.startsWith("http")) {
    return alignConfiguredLoopbackHost(url);
  }
  if (typeof window === "undefined") {
    return url;
  }
  return `${window.location.protocol}//${window.location.host}${url}`;
}

export function getWsUrl(): string {
  const url = env.NEXT_PUBLIC_WS_URL;
  if (url.startsWith("ws")) {
    return alignConfiguredLoopbackHost(url);
  }
  if (typeof window === "undefined") {
    return url;
  }
  return `${window.location.protocol === "https:" ? "wss" : "ws"}://${
    window.location.host
  }${url}`;
}

export function alignConfiguredLoopbackHost(
  configuredUrl: string,
  browserUrl?: string,
): string {
  const currentUrl =
    browserUrl ?? (typeof window === "undefined" ? undefined : window.location.href);
  if (!currentUrl) return configuredUrl;

  try {
    const configured = new URL(configuredUrl);
    if (!isLoopbackHost(configured.hostname)) return configuredUrl;

    const current = new URL(currentUrl);
    if (!current.hostname || configured.hostname === current.hostname) {
      return configuredUrl;
    }

    configured.hostname = current.hostname;
    const normalized = configured.toString();
    return configuredUrl.endsWith("/") ? normalized : normalized.replace(/\/$/, "");
  } catch {
    return configuredUrl;
  }
}

function isLoopbackHost(hostname: string): boolean {
  return (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "[::1]" ||
    hostname === "::1"
  );
}

/* eslint-disable */
export async function request<T = any>(
  api: string,
  requestInit?: RequestInit,
): Promise<T> {
  const method = requestInit?.method ?? "GET";
  const csrfToken = readCookie("tf_csrf");
  const csrfHeaders = CSRF_EXEMPT_PATHS.has(api)
    ? {}
    : browserWriteHeaders(method, csrfToken);
  const defaultHeaders = {
    "Content-Type": "application/json",
  };

  const response = await fetch(`${getApiUrl()}${api}`, {
    ...requestInit,
    credentials: "include",
    headers: {
      ...defaultHeaders,
      ...csrfHeaders,
      ...requestInit?.headers,
    },
  });
  const responseText = await response.text();
  if (isSessionTerminal(response.status) && typeof window !== "undefined") {
    window.dispatchEvent(
      new CustomEvent(SESSION_TERMINAL_EVENT, {
        detail: { status: response.status },
      }),
    );
  }
  if (!responseText) {
    if (!response.ok) {
      throw new HttpError(response.status, "Request failed");
    }
    return undefined as T;
  }
  let data;
  try {
    data = JSON.parse(responseText);
  } catch (e) {
    throw new RequestParsedError(responseText);
  }
  if (!response.ok) {
    const error = data.error;
    throw new HttpError(
      response.status,
      typeof error === "string"
        ? error
        : (error?.message ?? `Request failed with status ${response.status}`),
      typeof error === "object" ? error?.code : undefined,
    );
  }

  return data as T;
}

export class HttpError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code?: string,
  ) {
    super(message);
    this.name = "HttpError";
  }
}

function readCookie(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  const prefix = `${encodeURIComponent(name)}=`;
  const entry = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : undefined;
}

export class RequestParsedError extends Error {
  responseText: string;

  constructor(responseText: string) {
    super("Parse JSON Error");
    this.responseText = responseText;
  }
}

export function localStorageProvider() {
  const map = new Map<string, any>(
    JSON.parse(localStorage.getItem("telegram-files") ?? "[]"),
  );

  window.addEventListener("beforeunload", () => {
    const appCache = JSON.stringify(Array.from(map.entries()));
    localStorage.setItem("telegram-files", appCache);
  });

  return map;
}

export async function POST(api: string, data?: any): Promise<any> {
  return await request(api, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export type TelegramApiArg = {
  data: any;
  method: string;
};

export async function telegramApi(
  api: string,
  {
    arg,
  }: {
    arg: TelegramApiArg;
  },
): Promise<any> {
  return await request(`${api}/${arg.method}`, {
    method: "POST",
    body: arg.data ? JSON.stringify(arg.data) : undefined,
  });
}
