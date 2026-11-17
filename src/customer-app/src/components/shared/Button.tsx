import type { ButtonHTMLAttributes } from "react";
import clsx from "clsx";

type Variant = "primary" | "ghost" | "danger";

export default function Button({
  variant = "primary",
  className,
  type = "button",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type={type}
      className={clsx(
        variant === "primary" && "inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white",
        variant === "ghost" && "btn-ghost",
        variant === "danger" && "inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white bg-[var(--error)]",
        className,
      )}
      style={variant === "primary" ? { background: "var(--brand-fill)" } : undefined}
      {...props}
    />
  );
}
