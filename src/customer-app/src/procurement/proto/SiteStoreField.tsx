// @ts-nocheck
import { useStore } from "./runtime";

export default function SiteStoreField({ siteId, storeId, onChange, label = "Receiving site", storeLabel = "Receiving store", storeTypes = null }) {
  const { sites, storeLocations } = useStore();
  const stores = storeLocations.filter((location) =>
    (!siteId || location.siteId === siteId) &&
    (!storeTypes || storeTypes.includes(location.locationType))
  );
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      <label className="block text-xs font-medium text-[var(--text-2)]">
        {label} *
        <select
          className="eoss-input w-full mt-1"
          value={siteId}
          onChange={(event) => {
            const site = sites.find((item) => item.id === event.target.value);
            const nextStores = storeLocations.filter((location) =>
              location.siteId === event.target.value &&
              (!storeTypes || storeTypes.includes(location.locationType))
            );
            const store = nextStores[0];
            onChange({
              siteId: site?.id ?? "",
              siteName: site?.name ?? "",
              storeId: store?.id ?? "",
              storeName: store?.name ?? "",
            });
          }}
        >
          <option value="">Select a site…</option>
          {sites.map((site) => <option key={site.id} value={site.id}>{site.name}</option>)}
        </select>
      </label>
      <label className="block text-xs font-medium text-[var(--text-2)]">
        {storeLabel}
        <select
          className="eoss-input w-full mt-1"
          value={storeId}
          onChange={(event) => {
            const site = sites.find((item) => item.id === siteId);
            const store = stores.find((item) => item.id === event.target.value);
            onChange({
              siteId: site?.id ?? siteId,
              siteName: site?.name ?? "",
              storeId: store?.id ?? "",
              storeName: store?.name ?? "",
            });
          }}
        >
          <option value="">No store selected</option>
          {stores.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}
        </select>
      </label>
    </div>
  );
}
