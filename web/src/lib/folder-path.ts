const MAX_FOLDER_PATH_LENGTH = 4096;
const INVALID_FOLDER_PATH_CHARACTERS = '<>:"|?*';

export function isValidFolderPath(path: string): boolean {
  if (path.length === 0 || path.length > MAX_FOLDER_PATH_LENGTH) {
    return false;
  }

  const normalizedPath = path.replaceAll("\\", "/");
  const segments = normalizedPath.split("/");
  const contentSegments = segments.slice(
    normalizedPath.startsWith("/") ? 1 : 0,
    normalizedPath.endsWith("/") ? -1 : undefined,
  );

  if (contentSegments.length === 0) {
    return normalizedPath === "/";
  }

  return contentSegments.every(
    (segment) =>
      segment.length > 0 &&
      [...segment].every(
        (character) => !INVALID_FOLDER_PATH_CHARACTERS.includes(character),
      ),
  );
}
