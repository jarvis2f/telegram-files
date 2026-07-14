import type { TelegramFile } from "@/lib/types";

export type SharePolicyCategory = {
  id: string;
  label: string;
  defaultForFileTypes: string[];
};

export type SharePolicyRule = {
  id: string;
  decision: "ALLOW" | "DENY";
  reason: string;
  match: {
    fileTypes?: string[];
    mimeTypes?: string[];
    mimeTypePrefixes?: string[];
    minFileSizeBytes?: number;
    maxFileSizeBytes?: number;
  };
};

export type SharePublicationPolicy = {
  defaultDecision: "ALLOW" | "DENY";
  defaultCategoryId: string;
  categories: SharePolicyCategory[];
  shareRules: SharePolicyRule[];
};

export const DEFAULT_SHARE_PUBLICATION_POLICY: SharePublicationPolicy = {
  defaultDecision: "ALLOW",
  defaultCategoryId: "file",
  categories: [
    { id: "file", label: "File", defaultForFileTypes: ["file"] },
    { id: "video", label: "Video", defaultForFileTypes: ["video"] },
    { id: "audio", label: "Audio", defaultForFileTypes: ["audio"] },
    { id: "archive", label: "Archive", defaultForFileTypes: [] },
    { id: "document", label: "Document", defaultForFileTypes: [] },
    { id: "other", label: "Other", defaultForFileTypes: [] },
  ],
  shareRules: [
    {
      id: "deny-preview-types",
      decision: "DENY",
      reason: "File type is not shareable",
      match: { fileTypes: ["thumbnail", "photo"] },
    },
    {
      id: "deny-small-files",
      decision: "DENY",
      reason: "File is smaller than the minimum share size",
      match: { maxFileSizeBytes: 50 * 1024 * 1024 - 1 },
    },
  ],
};

export function canShareFile(
  file: TelegramFile,
  policy: SharePublicationPolicy = DEFAULT_SHARE_PUBLICATION_POLICY,
) {
  for (const rule of policy.shareRules) {
    if (!matchesRule(file, rule.match)) continue;
    return rule.decision === "ALLOW";
  }
  return policy.defaultDecision === "ALLOW";
}

export function categoryForFile(
  file: TelegramFile,
  policy: SharePublicationPolicy = DEFAULT_SHARE_PUBLICATION_POLICY,
) {
  return (
    policy.categories.find((category) =>
      category.defaultForFileTypes.includes(file.type),
    )?.id ?? policy.defaultCategoryId
  );
}

function matchesRule(file: TelegramFile, match: SharePolicyRule["match"]) {
  const mimeType = file.mimeType?.toLocaleLowerCase() ?? "";
  const mimeTypePrefixes = match.mimeTypePrefixes ?? [];
  return (
    matchesList(match.fileTypes, file.type) &&
    matchesList(match.mimeTypes, mimeType) &&
    (mimeTypePrefixes.length === 0 ||
      mimeTypePrefixes.some((prefix) => mimeType.startsWith(prefix))) &&
    (match.minFileSizeBytes === undefined || file.size >= match.minFileSizeBytes) &&
    (match.maxFileSizeBytes === undefined || file.size <= match.maxFileSizeBytes)
  );
}

function matchesList(values: string[] | undefined, candidate: string) {
  return (
    values === undefined ||
    values.length === 0 ||
    values.includes(candidate.toLocaleLowerCase())
  );
}
