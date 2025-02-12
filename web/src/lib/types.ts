import { type TelegramObject } from "@/lib/websocket-types";

export type TelegramAccount = {
  id: string;
  name: string;
  phoneNumber: string;
  avatar?: string;
  status: "active" | "inactive";
  lastAuthorizationState?: TelegramObject;
  proxy?: string;
  rootPath: string;
};

export type TelegramChat = {
  id: string;
  name: string;
  type: "private" | "group" | "channel";
  avatar?: string;
  unreadCount?: number;
  lastMessage?: string;
  lastMessageTime?: string;
  autoEnabled: boolean;
  autoRule?: AutoDownloadRule;
};

export type FileType = "media" | "photo" | "video" | "audio" | "file";
export type DownloadStatus =
  | "idle"
  | "downloading"
  | "paused"
  | "completed"
  | "error";

export type TransferStatus = "idle" | "transferring" | "completed" | "error";

export type TelegramFile = {
  id: number;
  telegramId: number;
  uniqueId: string;
  messageId: number;
  chatId: number;
  fileName: string;
  type: FileType;
  size: number;
  downloadedSize: number;
  thumbnail?: string;
  downloadStatus: DownloadStatus;
  date: number;
  formatDate: string;
  caption: string;
  localPath: string;
  hasSensitiveContent: boolean;
  startDate: number;
  completionDate: number;
  transferStatus?: TransferStatus;
  extra?: PhotoExtra | VideoExtra;

  prev?: TelegramFile;
  next?: TelegramFile;
};

export type PhotoExtra = {
  width: number;
  height: number;
  type: string;
};

export type VideoExtra = {
  width: number;
  height: number;
  duration: number;
  mimeType: string;
};

export type TDFile = {
  id: number;
  size: number;
  expectedSize: number;
  local?: {
    path: string;
    canBeDownloaded: boolean;
    canBeDeleted: boolean;
    isDownloadingActive: boolean;
    isDownloadingCompleted: boolean;
    downloadOffset: number;
    downloadedPrefixSize: number;
    downloadedSize: number;
  };
  remote: {
    id: number;
    uniqueId: string;
    isUploadingActive: boolean;
    isUploadingCompleted: boolean;
    uploadedSize: number;
  };
};

export type FileFilter = {
  search: string;
  type: FileType;
  downloadStatus?: DownloadStatus;
  transferStatus?: TransferStatus;
};

export type TelegramApiResult = {
  code: string;
};

export const SettingKeys = [
  "uniqueOnly",
  "imageLoadSize",
  "alwaysHide",
  "showSensitiveContent",
  "autoDownloadLimit",
  "proxys",
  "avgSpeedInterval",
] as const;

export type SettingKey = (typeof SettingKeys)[number];

export type Settings = Record<SettingKey, string>;

export type Proxy = {
  id?: string;
  name: string;
  server: string;
  port: number;
  username: string;
  password: string;
  type: "http" | "socks5";
  isEnabled?: boolean;
};

export const TransferPolices = ["GROUP_BY_CHAT", "GROUP_BY_TYPE"] as const;
export type TransferPolicy = (typeof TransferPolices)[number];
export const DuplicationPolicies = [
  "OVERWRITE",
  "RENAME",
  "SKIP",
  "HASH",
] as const;
export type DuplicationPolicy = (typeof DuplicationPolicies)[number];

export type TransferRule = {
  transferHistory: boolean;
  destination: string;
  transferPolicy: TransferPolicy;
  duplicationPolicy: DuplicationPolicy;
};

export type AutoDownloadRule = {
  query: string;
  fileTypes: Array<Exclude<FileType, "media">>;
  transferRule?: TransferRule;
};
