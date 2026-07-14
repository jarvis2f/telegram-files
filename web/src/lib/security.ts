const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export function requiresCsrf(method: string): boolean {
  return !SAFE_METHODS.has(method.toUpperCase());
}

export function browserWriteHeaders(method: string, csrfToken?: string): HeadersInit {
  if (!requiresCsrf(method)) return {};
  if (!csrfToken) throw new Error("A CSRF token is required for browser writes");
  return { "X-CSRF-Token": csrfToken };
}

export function isSessionTerminal(status: number): boolean {
  return status === 401 || status === 403;
}
