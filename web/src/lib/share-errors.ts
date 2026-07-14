const SHARE_ERROR_MESSAGES: Record<string, string> = {
  PLATFORM_ACCESS_BLOCKED:
    "The platform is behind an interactive access gateway. Allow machine access to /api/v1 or configure a service-token policy.",
  PLATFORM_HTTP_401: "The platform rejected this node credential. Rebind the node and retry.",
  PLATFORM_HTTP_403: "The platform denied this node permission to publish the source.",
  PLATFORM_HTTP_404:
    "The configured platform does not expose the M2 resource endpoint. Deploy the current telegram-seed version.",
  NODE_REVOKED: "The platform node was revoked. Rebind this node before retrying.",
  VALIDATION_FAILED: "The platform rejected the resource metadata.",
  INTERNAL_RETRYABLE: "The platform could not be reached or returned an unexpected response.",
};

export function describeShareError(code: string | null): string {
  if (!code) return "Platform synchronization failed without a stable error code.";
  return SHARE_ERROR_MESSAGES[code] ?? `Platform synchronization failed (${code}).`;
}
