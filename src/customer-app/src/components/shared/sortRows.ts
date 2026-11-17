import type { SortDirection } from "./SortableHeader";

export function compareSortValues(left: unknown, right: unknown) {
  if (left == null && right == null) return 0;
  if (left == null) return 1;
  if (right == null) return -1;
  if (typeof left === "number" && typeof right === "number") return left - right;
  return String(left).localeCompare(String(right), undefined, { numeric: true, sensitivity: "base" });
}

export function sortRows<T>(rows: T[], key: string, direction: SortDirection, value: (row: T, key: string) => unknown) {
  const multiplier = direction === "asc" ? 1 : -1;
  return [...rows].sort((left, right) => multiplier * compareSortValues(value(left, key), value(right, key)));
}
