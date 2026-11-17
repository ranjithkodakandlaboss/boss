import type { InputHTMLAttributes, SelectHTMLAttributes } from "react";

export function TextField({ label, ...props }: { label: string } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="block text-xs font-medium text-[var(--text-2)]">
      {label}
      <input className="eoss-input w-full mt-1" {...props} />
    </label>
  );
}

export function SelectField({
  label,
  children,
  ...props
}: { label: string } & SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <label className="block text-xs font-medium text-[var(--text-2)]">
      {label}
      <select className="eoss-input w-full mt-1 min-h-[36px]" {...props}>
        {children}
      </select>
    </label>
  );
}
