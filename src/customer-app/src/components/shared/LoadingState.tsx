export default function LoadingState({ label = "Loading records" }: { label?: string }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3 text-center" role="status">
      <div className="w-8 h-8 rounded-full border-2 border-[var(--border)] border-t-[var(--brand)] animate-spin" />
      <p className="text-sm font-medium text-[var(--text-2)]">{label}</p>
    </div>
  );
}
