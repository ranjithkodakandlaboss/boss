import { AlertTriangle } from "lucide-react";

export default function ErrorState({ title = "Records unavailable", body }: { title?: string; body: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-center" role="alert">
      <div className="w-12 h-12 bg-[var(--error-bg)] rounded-full flex items-center justify-center text-[var(--error)]">
        <AlertTriangle size={22} />
      </div>
      <div>
        <p className="text-sm font-medium text-[var(--text)]">{title}</p>
        <p className="text-xs text-[var(--text-2)] mt-1 max-w-sm">{body}</p>
      </div>
    </div>
  );
}
