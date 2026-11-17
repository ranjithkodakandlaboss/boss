import { api, safeRandomUUID } from "../../auth/api";
import type { CompanyRecord } from "../../auth/types";
import { createItem, createUom, type ItemOption, type UomOption } from "../api";
import type { Party } from "./types";

export function newCustomer(legalName: string, city: string): Party {
  const name = legalName.trim();
  const place = city.trim();
  return {
    id: "",
    partyType: "Customer",
    legalName: name,
    tradingName: name,
    panNumber: "",
    gstin: "",
    state: place,
    paymentTerms: "Net 30",
    currency: "INR",
    status: "active",
    contactPerson: name,
    phone: "",
    email: "",
    city: place,
    since: "",
  };
}

export async function saveCatalogItem(
  input: { code: string; name: string; uomId?: string },
  uoms: UomOption[],
): Promise<ItemOption> {
  const code = input.code.trim().toUpperCase();
  const name = input.name.trim();
  if (!code || !name) throw new Error("Enter an item code and name.");
  let uomId = input.uomId || uoms[0]?.id;
  if (!uomId) {
    const unit = await createUom({ name: "Numbers", symbol: "NOS", uomType: "quantity", isBase: true });
    uomId = unit.id;
  }
  return createItem({ code, name, itemType: "FINISHED_GOOD", uomId });
}

export async function saveUnit(name: string, symbol: string) {
  const cleanName = name.trim();
  const cleanSymbol = symbol.trim().toUpperCase();
  if (!cleanName || !cleanSymbol) throw new Error("Enter a unit name and symbol.");
  return createUom({ name: cleanName, symbol: cleanSymbol, uomType: "quantity", isBase: false });
}

export async function saveSite(name: string) {
  const clean = name.trim();
  if (!clean) throw new Error("Enter a site name.");
  const company = await api<CompanyRecord>("/api/v1/company");
  const code = clean.toUpperCase().replace(/[^A-Z0-9]+/g, "").slice(0, 8) || "SITE";
  const sites = [
    ...(company.sites ?? []),
    { name: clean, code, siteType: "factory", city: company.city ?? "", state: company.state ?? "" },
  ];
  return api<CompanyRecord>("/api/v1/company", {
    method: "PUT",
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify({ ...company, gstNotApplicable: !company.gstin, sites, stores: company.stores ?? [] }),
  });
}
