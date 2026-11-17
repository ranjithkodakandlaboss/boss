import type { ReactNode } from "react";
import EmptyState from "./EmptyState";
import LoadingState from "./LoadingState";
import ErrorState from "./ErrorState";
import SortableHeader from "./SortableHeader";
import type { RemoteList } from "../../api/records";

export default function DataTable({
  columns,
  state,
  emptyTitle,
}: {
  columns: string[];
  state: RemoteList;
  emptyTitle: string;
}) {
  return (
    <div className="overflow-auto">
      <table className="eoss-table hidden md:table">
        <thead>
          <tr>
            {columns.map((column) => (
              <SortableHeader key={column} label={column} />
            ))}
          </tr>
        </thead>
        <tbody />
      </table>
      {state.status === "loading" && <LoadingState label={state.message} />}
      {state.status === "error" && <ErrorState body={state.message} />}
      {state.status === "empty" && <EmptyState title={emptyTitle} body={state.message} />}
    </div>
  );
}

export function StateBody({ state, title, action }: { state: RemoteList; title: string; action?: ReactNode }) {
  if (state.status === "loading") return <LoadingState label={state.message} />;
  if (state.status === "error") return <ErrorState body={state.message} />;
  return <EmptyState title={title} body={state.message} action={action} />;
}
