export function placeLabel(site?: string, store?: string) {
  if (site && store) return `${site} · ${store}`;
  return site || store || "—";
}
