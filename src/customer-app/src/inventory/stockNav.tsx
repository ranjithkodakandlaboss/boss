import { NavLink } from "react-router-dom";

export const STOCK_LINKS = [
  { to: "/inventory", label: "Stock on hand", end: true },
  { to: "/inventory/opening-stock", label: "Opening stock" },
  { to: "/products", label: "Item master" },
  { to: "/inventory/transfer", label: "Move stock" },
  { to: "/inventory/transfers", label: "Transfers" },
  { to: "/inventory/returns", label: "Returns" },
  { to: "/allocations", label: "Site need" },
  { to: "/bom", label: "Bills of materials" },
];

export default function StockNav() {
  return (
    <nav aria-label="Inventory" className="flex gap-1 overflow-x-auto border-b px-3" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
      {STOCK_LINKS.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          className="whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium"
          style={({ isActive }) => ({
            color: isActive ? "var(--brand)" : "var(--text-2)",
            borderColor: isActive ? "var(--brand)" : "transparent",
          })}
        >
          {item.label}
        </NavLink>
      ))}
    </nav>
  );
}
