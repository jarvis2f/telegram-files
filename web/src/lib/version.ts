function normalizeVersion(version: string): string {
  return version.trim().replace(/^[vV]/, "").split(/[+-]/)[0] ?? "";
}

export function compareVersions(left: string, right: string): number {
  const leftParts = normalizeVersion(left).split(".");
  const rightParts = normalizeVersion(right).split(".");
  const length = Math.max(leftParts.length, rightParts.length);

  for (let index = 0; index < length; index += 1) {
    const leftValue = Number.parseInt(leftParts[index] ?? "0", 10);
    const rightValue = Number.parseInt(rightParts[index] ?? "0", 10);
    const normalizedLeft = Number.isNaN(leftValue) ? 0 : leftValue;
    const normalizedRight = Number.isNaN(rightValue) ? 0 : rightValue;

    if (normalizedLeft > normalizedRight) {
      return 1;
    }
    if (normalizedLeft < normalizedRight) {
      return -1;
    }
  }

  return 0;
}

export function isVersionNewer(latestVersion: string, currentVersion: string) {
  return compareVersions(latestVersion, currentVersion) > 0;
}
