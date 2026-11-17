import type { ReactNode } from "react";
import { Inbox } from "lucide-react";

export default function EmptyState({
  title = "No records",
  body,
  action,
  icon,
}: {
  title?: string;
  body?: string;
  action?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-center">
      <div className="w-12 h-12 bg-[var(--surface-2)] rounded-full flex items-center justify-center text-[var(--text-3)]">
        {icon ?? <Inbox size={22} />}
      </div>
      <div>
        <p className="text-sm font-medium text-[var(--text)]">{title}</p>
        {body && <p className="text-xs text-[var(--text-2)] mt-1 max-w-xs">{body}</p>}
      </div>
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
