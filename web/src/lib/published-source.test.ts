import { describe, expect, it } from "vitest";

import { publishedSourceFromTelegramFile } from "@/lib/published-source";
import { type TelegramFile } from "@/lib/types";

describe("publishedSourceFromTelegramFile", () => {
  it("uses the local share source id and preserves multiline descriptions", () => {
    const file = {
      id: 1,
      telegramId: 42,
      uniqueId: "unique-file",
      messageId: 2,
      chatId: 3,
      fileName: "archive.zip",
      type: "file",
      size: 1024,
      downloadedSize: 1024,
      downloadStatus: "completed",
      date: 0,
      formatDate: "",
      caption: "",
      localPath: "/tmp/archive.zip",
      hasSensitiveContent: false,
      startDate: 0,
      completionDate: 0,
      originalDeleted: false,
      loaded: true,
      threadChatId: 0,
      messageThreadId: 0,
      reactionCount: 0,
      sharedSourceId: "source-local-id",
      sharedResourceId: "seed-resource-id",
      shareTitle: "Indexed title",
      shareDescription: "line one\nline two",
      shareTags: ["docs", "seed"],
      shareCategory: "archive",
      shareAccessScope: "MEMBER_ACCESS",
      sharePublicMessageUrl: null,
      shareStatus: "PUBLISHED",
    } satisfies TelegramFile;

    const source = publishedSourceFromTelegramFile(file);

    expect(source).toMatchObject({
      sourceId: "source-local-id",
      resourceId: "seed-resource-id",
      description: "line one\nline two",
      accessScope: "MEMBER_ACCESS",
    });
  });
});
