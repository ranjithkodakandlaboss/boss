import { api } from "../auth/api";
import type { CreatableField, CreatableOption } from "../components/shared/CreatableSelect";
import { mutate, type Company, type Item, type Lot, type Site, type Store } from "./api";

const SITE_TYPES: CreatableOption[] = [
  { id: "factory", label: "Factory" },
  { id: "warehouse", label: "Warehouse" },
  { id: "office", label: "Office" },
  { id: "depot", label: "Depot" },
  { id: "other", label: "Other" },
];

const STORE_TYPES: CreatableOption[] = [
  { id: "raw_material", label: "Raw material" },
  { id: "finished_goods", label: "Finished goods" },
  { id: "work_in_progress", label: "Work in progress" },
  { id: "consumable", label: "Consumable" },
  { id: "tool", label: "Tool" },
  { id: "rejected", label: "Rejected" },
  { id: "quarantine", label: "Quarantine" },
];

export async function storeFields(): Promise<CreatableField[]> {
  const company = await api<Company>("/api/v1/company");
  const sites = company.sites ?? [];
  const siteField: CreatableField = sites.length > 0
    ? { name: "siteCode", label: "Site", required: true, options: sites.map((site) => ({ id: site.code, label: `${site.code} · ${site.name}` })) }
    : { name: "siteCode", label: "Site code", required: true };
  const fields: CreatableField[] = [siteField];
  if (sites.length === 0) {
    fields.push(
      { name: "siteName", label: "Site name", required: true },
      { name: "siteType", label: "Site type", required: true, options: SITE_TYPES },
    );
  }
  fields.push(
    { name: "name", label: "Store name", required: true },
    { name: "code", label: "Store code", required: true },
    { name: "storeType", label: "Store type", required: true, options: STORE_TYPES },
  );
  return fields;
}

export async function createStore(values: Record<string, string>): Promise<Store> {
  let siteCode = values.siteCode;
  if (values.siteName) {
    const site = await mutate<Site>("/api/v1/company/sites", "POST", {
      name: values.siteName, code: values.siteCode, siteType: values.siteType || "factory",
    });
    siteCode = site.code;
  }
  return mutate<Store>("/api/v1/company/stores", "POST", {
    siteCode, name: values.name, code: values.code, storeType: values.storeType || "raw_material",
  });
}

export async function lotFields(fixed: { itemId?: string; storeId?: string; siteId?: string }): Promise<CreatableField[]> {
  const fields: CreatableField[] = [];
  if (!fixed.itemId) {
    const page = await api<{ content?: Item[] }>("/api/v1/items?status=active&page=0&size=200");
    fields.push({
      name: "itemId",
      label: "Item",
      required: true,
      options: (page.content ?? []).map((item) => ({ id: item.id, label: `${item.code} · ${item.name}` })),
    });
  }
  if (!fixed.storeId) {
    const company = await api<Company>("/api/v1/company");
    const stores = (company.stores ?? []).filter((store) => !fixed.siteId || store.siteId === fixed.siteId);
    fields.push({
      name: "storeId",
      label: "Store",
      required: true,
      options: stores.map((store) => ({ id: store.id, label: store.siteCode ? `${store.siteCode} · ${store.name}` : store.name })),
    });
  }
  fields.push(
    { name: "qty", label: "Quantity", required: true },
    { name: "batch", label: "Batch" },
  );
  return fields;
}

export function createLot(values: Record<string, string>, fixed: { itemId?: string; storeId?: string }) {
  return mutate<Lot>("/api/v1/inventory/lots", "POST", {
    itemId: fixed.itemId || values.itemId,
    storeId: fixed.storeId || values.storeId,
    qty: Number(values.qty),
    batchRef: values.batch || null,
  });
}
