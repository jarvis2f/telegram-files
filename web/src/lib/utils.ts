import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";
import { type Proxy } from "@/lib/types";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function parseProxyString(proxyString: string): Proxy | null {
  // 匹配 mtproto 格式：mtproto://server:port?secret=xxx
  const mtprotoRegex = /^mtproto:\/\/([^:]+):(\d+)(\?secret=([^&]+))?$/i;
  const mtprotoMatch = mtprotoRegex.exec(proxyString);
  if (mtprotoMatch) {
    const server = mtprotoMatch[1] ?? "";
    const port = parseInt(mtprotoMatch[2] ?? "0", 10);
    const secret = mtprotoMatch[4] ?? "";

    return {
      name: "mtproto proxy",
      server,
      port,
      username: "",
      password: "",
      secret,
      type: "mtproto",
    };
  }

  const proxyRegex =
    /^(http|socks|socks5):\/\/(([^:]+):([^@]+)@)?([^:]+):(\d+)$/i;
  const match = proxyRegex.exec(proxyString);

  if (!match) {
    return null;
  }

  let type = match[1];
  if (type === "http") {
    type = "http";
  } else {
    type = "socks5";
  }
  const username = match[3] ?? "";
  const password = match[4] ?? "";
  const server = match[5] ?? "";
  const port = parseInt(match[6] ?? "0", 10);

  return {
    name: `${type} proxy`,
    server,
    port,
    username,
    password,
    secret: "",
    type: type as "http" | "socks5",
  };
}

export function split(separator: string, str?: string): string[] {
  if (!str || str.length === 0) {
    return [];
  }
  return str
    .split(separator)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}
