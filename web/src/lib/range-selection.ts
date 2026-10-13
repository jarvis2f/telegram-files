/**
 * Computes the next selection after a row checkbox was clicked.
 *
 * A plain click toggles `id`. A shift-click applies the state the click gives
 * `id` to every row between `anchorId` (the previously clicked row) and `id`,
 * inclusive, in the order of `items`. If there is no usable anchor (nothing
 * clicked yet, or the anchor is no longer in `items`), it falls back to a
 * plain toggle.
 */
export function nextSelection(
  selected: ReadonlySet<number>,
  items: ReadonlyArray<{ id: number }>,
  id: number,
  anchorId: number | null,
  shiftKey: boolean,
): Set<number> {
  const next = new Set(selected);
  const select = !selected.has(id);

  const index = items.findIndex((item) => item.id === id);
  const anchorIndex =
    anchorId === null ? -1 : items.findIndex((item) => item.id === anchorId);

  if (!shiftKey || index === -1 || anchorIndex === -1) {
    if (select) {
      next.add(id);
    } else {
      next.delete(id);
    }
    return next;
  }

  const from = Math.min(index, anchorIndex);
  const to = Math.max(index, anchorIndex);
  for (let i = from; i <= to; i++) {
    const itemId = items[i]!.id;
    if (select) {
      next.add(itemId);
    } else {
      next.delete(itemId);
    }
  }
  return next;
}
