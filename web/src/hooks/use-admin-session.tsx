"use client";

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { request, SESSION_TERMINAL_EVENT } from "@/lib/api";

export interface AdminSession {
  authenticated: true;
  username: string;
  idleExpiresAt: number;
  absoluteExpiresAt: number;
}

type SessionStatus = "loading" | "bootstrap" | "unauthenticated" | "authenticated";

interface AdminSessionContextValue {
  status: SessionStatus;
  session: AdminSession | null;
  sessionExpired: boolean;
  login: (username: string, password: string) => Promise<void>;
  bootstrap: (
    bootstrapToken: string,
    username: string,
    password: string,
  ) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

const AdminSessionContext = createContext<AdminSessionContextValue | undefined>(
  undefined,
);

export function AdminSessionProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<SessionStatus>("loading");
  const [session, setSession] = useState<AdminSession | null>(null);
  const [sessionExpired, setSessionExpired] = useState(false);

  const refresh = useCallback(async () => {
    setStatus("loading");
    try {
      const bootstrapState = await request<{ required: boolean }>(
        "/auth/bootstrap/status",
      );
      if (bootstrapState.required) {
        setSession(null);
        setStatus("bootstrap");
        return;
      }
      const activeSession = await request<AdminSession>("/auth/session");
      setSession(activeSession);
      setSessionExpired(false);
      setStatus("authenticated");
    } catch {
      setSession(null);
      setStatus("unauthenticated");
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    const onSessionTerminal = () => {
      setSession((current) => {
        if (current) setSessionExpired(true);
        return null;
      });
      setStatus("unauthenticated");
    };
    window.addEventListener(SESSION_TERMINAL_EVENT, onSessionTerminal);
    return () =>
      window.removeEventListener(SESSION_TERMINAL_EVENT, onSessionTerminal);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const activeSession = await request<AdminSession>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
    setSession(activeSession);
    setSessionExpired(false);
    setStatus("authenticated");
  }, []);

  const bootstrap = useCallback(
    async (bootstrapToken: string, username: string, password: string) => {
      const activeSession = await request<AdminSession>("/auth/bootstrap", {
        method: "POST",
        body: JSON.stringify({ bootstrapToken, username, password }),
      });
      setSession(activeSession);
      setSessionExpired(false);
      setStatus("authenticated");
    },
    [],
  );

  const logout = useCallback(async () => {
    try {
      await request<void>("/auth/logout", { method: "POST" });
    } finally {
      setSession(null);
      setSessionExpired(false);
      setStatus("unauthenticated");
    }
  }, []);

  const value = useMemo(
    () => ({
      status,
      session,
      sessionExpired,
      login,
      bootstrap,
      logout,
      refresh,
    }),
    [status, session, sessionExpired, login, bootstrap, logout, refresh],
  );

  return (
    <AdminSessionContext.Provider value={value}>
      {children}
    </AdminSessionContext.Provider>
  );
}

export function useAdminSession(): AdminSessionContextValue {
  const context = useContext(AdminSessionContext);
  if (!context) {
    throw new Error("useAdminSession must be used inside AdminSessionProvider");
  }
  return context;
}
