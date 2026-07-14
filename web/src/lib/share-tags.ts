import type { TelegramFile } from "@/lib/types";

const HASHTAG = /(?:^|\s)#([^#\s]+)/gu;
const MAX_TAGS = 32;
const MAX_TAG_LENGTH = 64;

export function defaultShareTags(file: TelegramFile) {
  return mergeShareTags(file.tags ?? "", file.caption ?? "", file.hasSensitiveContent);
}

export function mergeShareTags(
  manualTags: string,
  description: string | null | undefined,
  hasSensitiveContent: boolean,
) {
  const tags: string[] = [];
  const unique = new Set<string>();

  for (const raw of manualTags.split(",")) {
    addTag(tags, unique, raw);
  }

  if (description) {
    for (const match of description.matchAll(HASHTAG)) {
      addTag(tags, unique, match[1]);
    }
  }

  if (hasSensitiveContent) {
    addTag(tags, unique, "R18");
  }

  return tags.join(", ");
}

function addTag(tags: string[], unique: Set<string>, raw: string | undefined) {
  if (tags.length >= MAX_TAGS || raw === undefined) return;
  const tag = raw.trim().replace(/\s+/g, " ");
  if (tag.length === 0 || tag.length > MAX_TAG_LENGTH) return;
  const key = tag.toLocaleLowerCase();
  if (unique.has(key)) return;
  unique.add(key);
  tags.push(tag);
}
