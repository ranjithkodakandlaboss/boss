import React from "react";
import { AlertTriangle } from "lucide-react";

export default function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  variant = "primary",
  loading,
  onConfirm,
  onCancel,
  children,
}: {
  open: boolean;
  title: string;
  body?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: "danger" | "primary";
  loading?: boolean;
  onConfirm: () => void | Promise<void>;
  onCancel: () => void;
  children?: React.ReactNode;
}) {
  const [submitting, setSubmitting] = React.useState(false);

  React.useEffect(() => {
    if (open) setSubmitting(false);
  }, [open]);

  if (!open) return null;

  const isBusy = submitting || Boolean(loading);

  const handleConfirm = async (e: React.MouseEvent) => {
    e.preventDefault();
    if (isBusy) return;
    setSubmitting(true);
    try {
      await onConfirm();
    } catch {
      setSubmitting(false);
    }
  };

  return (
    <div data-overlay="center" className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm">
      <div role="dialog" aria-modal="true" aria-labelledby="confirm-title" className="bg-[var(--surface)] border border-[var(--border)] rounded-xl shadow-xl max-w-md w-full p-6">
        <div className="flex items-start gap-3">
          <div className={`w-10 h-10 rounded-full flex items-center justify-center flex-shrink-0 ${variant === "danger" ? "bg-[var(--error-bg)]" : "bg-[var(--info-bg)]"}`}>
            <AlertTriangle size={18} className={variant === "danger" ? "text-[var(--error)]" : "text-[var(--info)]"} />
          </div>
          <div className="flex-1">
            <h3 id="confirm-title" className="font-semibold text-[var(--text)]">{title}</h3>
            {body && <p className="text-sm text-[var(--text-2)] mt-1">{body}</p>}
            {children}
          </div>
        </div>
        <div className="flex gap-3 mt-6 justify-end">
          <button
            type="button"
            disabled={isBusy}
            onClick={onCancel}
            className="px-4 py-2 rounded-lg border border-[var(--border)] text-sm text-[var(--text-2)] hover:bg-[var(--surface-2)] transition-colors disabled:opacity-50"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            disabled={isBusy}
            onClick={handleConfirm}
            className={`px-4 py-2 rounded-lg text-sm font-medium text-white transition-colors disabled:opacity-50 ${variant === "danger" ? "bg-[var(--error)] hover:opacity-90" : "bg-[var(--brand-fill)] hover:bg-[var(--brand-700)]"}`}
          >
            {isBusy ? "Processing…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
