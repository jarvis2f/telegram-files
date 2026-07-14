export type ColumnPreference = {
  id: string;
  isVisible: boolean;
};

export function applyColumnPreferences<
  T extends { id: string; isVisible: boolean },
>(defaults: T[], preferences: ColumnPreference[]): T[] {
  const defaultsById = new Map(defaults.map((column) => [column.id, column]));
  const configuredIds = new Set<string>();
  const configured = preferences.flatMap((preference) => {
    const column = defaultsById.get(preference.id);
    if (!column || configuredIds.has(preference.id)) {
      return [];
    }
    configuredIds.add(preference.id);
    return [{ ...column, isVisible: preference.isVisible }];
  });

  return [
    ...configured,
    ...defaults.filter((column) => !configuredIds.has(column.id)),
  ];
}
