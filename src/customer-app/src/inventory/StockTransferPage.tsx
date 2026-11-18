import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api, safeRandomUUID } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import Button from "../components/shared/Button";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import LoadingState from "../components/shared/LoadingState";
import { createLot, createStore, lotFields, storeFields } from "./inlineCreate";
import StockNav from "./stockNav";

type Store = { id: string; siteId: string; name: string; siteCode?: string | null };
type Company = { stores?: Store[] };

function storeLabel(store: Store) {
  return store.siteCode ? `${store.siteCode} · ${store.name}` : store.name;
}
type Page<T> = { content: T[]; totalElements: number };
type Lot = {
  id: string;
  itemCode: string;
  itemName: string;
  batchRef: string | null;
  lotNumber?: string | null;
  qty: number;
  availableQty: number;
  uomSymbol: string;
  status: string;
};

async function loadLots(storeId: string): Promise<Lot[]> {
  const rows: Lot[] = [];
  let page = 0;
  let total = Number.POSITIVE_INFINITY;
  while (rows.length < total) {
    const result = await api<Page<Lot>>(
      `/api/v1/inventory/lots?storeId=${encodeURIComponent(storeId)}&page=${page}&size=200`,
    );
    rows.push(...result.content);
    total = result.totalElements;
    page += 1;
    if (result.content.length === 0) break;
  }
  return rows.filter((lot) => (lot.status === "available" || lot.status === "reserved") && lot.availableQty > 0);
}

export default function StockTransferPage() {
  const navigate = useNavigate();
  const { user } = useCustomer();
  const canCreateStore = roleAllowed(user?.roles, ["OWNER"]);
  const canCreateLot = roleAllowed(user?.roles, ["OWNER", "OPS_MANAGER", "STORE_KEEPER"]);
  const [searchParams] = useSearchParams();
  const requestedFrom = searchParams.get("from") ?? "";
  const requestedTo = searchParams.get("to") ?? "";
  const requestedMaterial = searchParams.get("material") ?? "";
  const requestedQty = searchParams.get("qty") ?? "";
  const [stores, setStores] = useState<Store[]>([]);
  const [sourceStoreId, setSourceStoreId] = useState("");
  const [destinationStoreId, setDestinationStoreId] = useState("");
  const [lots, setLots] = useState<Lot[]>([]);
  const [lotId, setLotId] = useState("");
  const [qty, setQty] = useState(requestedQty);
  const [note, setNote] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingLots, setLoadingLots] = useState(false);
  const [lotReload, setLotReload] = useState(0);
  const [saving, setSaving] = useState(false);
  const [storeError, setStoreError] = useState("");
  const [lotError, setLotError] = useState("");
  const [error, setError] = useState("");

  function loadStoreList() {
    setLoading(true);
    setStoreError("");
    api<Company>("/api/v1/company").then((company) => {
      const list = company.stores ?? [];
      setStores(list);
      const requested = list.find((store) => store.name === requestedFrom);
      setSourceStoreId((current) => current || requested?.id || list[0]?.id || "");
    }).catch((reason: unknown) => {
      setStoreError(reason instanceof Error ? reason.message : "Stores could not be loaded.");
    }).finally(() => setLoading(false));
  }

  useEffect(() => { loadStoreList(); }, []);

  useEffect(() => {
    if (!sourceStoreId) {
      setLots([]);
      setLotId("");
      return;
    }
    let active = true;
    setLots([]);
    setLotId("");
    setLoadingLots(true);
    setLotError("");
    loadLots(sourceStoreId).then((result) => {
      if (!active) return;
      setLots(result);
      const match = result.find((lot) => lot.itemCode === requestedMaterial) ?? result[0];
      setLotId(match?.id ?? "");
      if (requestedTo) {
        const destination = stores.find((store) => store.name === requestedTo && store.id !== sourceStoreId);
        if (destination) setDestinationStoreId(destination.id);
      }
    }).catch((reason: unknown) => {
      if (active) setLotError(reason instanceof Error ? reason.message : "Source stock could not be loaded.");
    }).finally(() => { if (active) setLoadingLots(false); });
    return () => { active = false; };
  }, [sourceStoreId, lotReload]);

  const sourceStore = stores.find((store) => store.id === sourceStoreId);
  const destinations = useMemo(
    () => stores.filter((store) => store.siteId !== sourceStore?.siteId),
    [sourceStore?.siteId, stores],
  );
  const selectedLot = lots.find((lot) => lot.id === lotId);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedLot || !destinationStoreId || Number(qty) <= 0 || Number(qty) > selectedLot.availableQty) {
      setError("Choose a destination and enter a quantity within the lot's available balance.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      await api("/api/v1/inter-site-transfers", {
        method: "POST",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({
          fromStoreId: sourceStoreId,
          toStoreId: destinationStoreId,
          transferDate: new Date().toISOString().slice(0, 10),
          note: note.trim() || undefined,
          lines: [{ lotId, qty: Number(qty) }],
        }),
      });
      navigate("/inventory/transfers");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Transfer could not be created.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <LoadingState label="Loading stores" />;

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
    <div className="mx-auto w-full max-w-3xl px-6 py-6">
      <Link to="/inventory" className="text-sm font-medium text-[var(--brand)]">← Stock on hand</Link>
      <h1 className="mt-4 text-lg font-bold text-[var(--text)]">Move stock between sites</h1>
      <p className="mt-1 text-sm text-[var(--text-2)]">Available stock is deducted when dispatched and added to the destination when received.</p>
      <form className="mt-5 space-y-4 rounded-xl border p-5" style={{ borderColor: "var(--border)", background: "var(--surface)" }} onSubmit={(event) => void submit(event)}>
        <div className="block text-sm font-medium">
          Source store
          <CreatableSelect label="store" ariaLabel="Source store" value={sourceStoreId} error={storeError} onRetry={loadStoreList} creatable={canCreateStore} options={stores.map((store) => ({ id: store.id, label: storeLabel(store) }))} prepare={storeFields} onChange={(id) => { setSourceStoreId(id); setDestinationStoreId(""); }} onCreate={async (values) => {
            const created = await createStore(values);
            setStores((current) => [...current.filter((store) => store.id !== created.id), created]);
            return { id: created.id, label: storeLabel(created) };
          }} />
        </div>
        <div className="block text-sm font-medium">
          Destination store
          <CreatableSelect label="store" ariaLabel="Destination store" value={destinationStoreId} placeholder="Select a different site" error={storeError} onRetry={loadStoreList} creatable={canCreateStore} options={destinations.map((store) => ({ id: store.id, label: storeLabel(store) }))} prepare={storeFields} onChange={setDestinationStoreId} onCreate={async (values) => {
            const created = await createStore(values);
            setStores((current) => [...current.filter((store) => store.id !== created.id), created]);
            return { id: created.id, label: storeLabel(created) };
          }} />
        </div>
        {loadingLots ? <LoadingState label="Loading source lots" />
          : <div className="block text-sm font-medium">
            Lot
            <CreatableSelect label="lot" ariaLabel="Lot" value={lotId} placeholder="Select a lot" error={lotError} onRetry={() => setLotReload((current) => current + 1)} creatable={canCreateLot && !!sourceStoreId} options={lots.map((lot) => ({ id: lot.id, label: `${lot.itemCode} · ${lot.itemName}${(lot.lotNumber || lot.batchRef) ? ` · ${lot.lotNumber || lot.batchRef}` : ""} · ${lot.availableQty} ${lot.uomSymbol} available` }))} prepare={() => lotFields({ storeId: sourceStoreId })} onChange={(id) => { setLotId(id); setQty(""); }} onCreate={async (values) => {
              const created = await createLot(values, { storeId: sourceStoreId });
              const option = { id: created.id, itemCode: created.itemCode, itemName: created.itemName, batchRef: created.batchRef ?? null, lotNumber: created.lotNumber, qty: created.qty, availableQty: created.availableQty, uomSymbol: created.uomSymbol, status: created.status };
              setLots((current) => [...current.filter((lot) => lot.id !== created.id), option]);
              return { id: created.id, label: `${option.itemCode} · ${option.itemName}` };
            }} />
          </div>}
        <label className="block text-sm font-medium">
          Quantity {selectedLot && <span className="font-normal text-[var(--text-2)]">(up to {selectedLot.availableQty} {selectedLot.uomSymbol})</span>}
          <input className="eoss-input mt-1 w-full" type="number" min="0.001" max={selectedLot?.availableQty} step="0.001" required value={qty} onChange={(event) => setQty(event.target.value)} />
        </label>
        <label className="block text-sm font-medium">
          Transfer note
          <textarea className="eoss-input mt-1 w-full" rows={2} value={note} onChange={(event) => setNote(event.target.value)} placeholder="Optional reason or reference" />
        </label>
        {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => navigate("/inventory/transfers")}>Cancel</Button>
          <Button type="submit" disabled={saving || loadingLots || !lots.length}>{saving ? "Dispatching…" : "Dispatch transfer"}</Button>
        </div>
      </form>
    </div>
    </div>
  );
}
