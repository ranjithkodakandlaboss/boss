import type { ReactNode } from "react";
import EmptyState from "./EmptyState";
import ErrorState from "./ErrorState";
import LoadingState from "./LoadingState";
import SortableHeader, { type SortDirection } from "./SortableHeader";
import { sortRows } from "./sortRows";

export type RecordColumn<T> = {
  key: string;
  label: string;
  align?: "left" | "right" | "center";
  render: (row: T) => ReactNode;
  value?: (row: T) => unknown;
};

export default function RecordTable<T extends { id: string }>({
  rows,
  columns,
  sortKey,
  sortDir = "asc",
  onSort,
  onRow,
  selectedId,
  empty,
  loading,
  error,
  trailing,
}: {
  rows: T[];
  columns: RecordColumn<T>[];
  sortKey?: string;
  sortDir?: SortDirection;
  onSort?: (key: string) => void;
  onRow?: (row: T) => void;
  selectedId?: string | null;
  empty: string;
  loading?: boolean;
  error?: string;
  trailing?: (row: T) => ReactNode;
}) {
  if (loading) return <LoadingState label="Loading records" />;
  if (error) return <ErrorState body={error} />;
  const sorted = sortKey ? sortRows(rows, sortKey, sortDir, (row, key) => columns.find((column) => column.key === key)?.value?.(row) ?? "") : rows;
  return (
    <table className="eoss-table">
      <thead className="sticky top-0 z-10" style={{ background: "var(--surface-2)" }}>
        <tr style={{ borderBottom: "1px solid var(--border)" }}>
          {columns.map((column) => (
            <SortableHeader
              key={column.key}
              label={column.label}
              active={sortKey === column.key}
              direction={sortDir}
              onSort={onSort && column.value ? () => onSort(column.key) : undefined}
              className={`px-4 py-2.5 text-xs font-semibold ${column.align === "right" ? "text-right" : column.align === "center" ? "text-center" : "text-left"}`}
              style={{ color: "var(--text-3)" }}
            />
          ))}
          {trailing && <th />}
        </tr>
      </thead>
      <tbody>
        {sorted.map((row) => {
          const selected = row.id === selectedId;
          return (
            <tr
              key={row.id}
              onClick={onRow ? () => onRow(row) : undefined}
              className={onRow ? "cursor-pointer" : undefined}
              style={{ borderBottom: "1px solid var(--border)", background: selected ? "var(--surface-2)" : "var(--surface)", borderLeft: selected ? "3px solid var(--brand)" : "3px solid transparent" }}
            >
              {columns.map((column) => (
                <td key={column.key} className={`px-4 py-3 ${column.align === "right" ? "text-right" : column.align === "center" ? "text-center" : ""}`}>
                  {column.render(row)}
                </td>
              ))}
              {trailing && <td className="px-4 py-3" onClick={(event) => event.stopPropagation()}>{trailing(row)}</td>}
            </tr>
          );
        })}
        {sorted.length === 0 && (
          <tr>
            <td colSpan={columns.length + (trailing ? 1 : 0)}>
              <EmptyState title={empty} body="Nothing matches the current search and filters." />
            </td>
          </tr>
        )}
      </tbody>
    </table>
  );
}
