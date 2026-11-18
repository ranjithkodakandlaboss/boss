import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, safeRandomUUID } from "../auth/api";
import Button from "../components/shared/Button";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";

type Store = { id: string; siteId: string; name: string };
type Company = { stores: Store[] };
type Page<T> = { content: T[]; totalElements: number };
type Lot = {
  id: string;
  itemCode: string;
  itemName: string;
  batchRef: string | null;
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
  const [stores, setStores] = useState<Store[]>([]);
  const [sourceStoreId, setSourceStoreId] = useState("");
  const [destinationStoreId, setDestinationStoreId] = useState("");
  const [lots, setLots] = useState<Lot[]>([]);
  const [lotId, setLotId] = useState("");
  const [qty, setQty] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingLots, setLoadingLots] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    api<Company>("/api/v1/company").then((company) => {
      if (!active) return;
      setStores(company.stores);
      setSourceStoreId(company.stores[0]?.id ?? "");
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "Stores could not be loaded.");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

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
    setError("");
    loadLots(sourceStoreId).then((result) => {
      if (!active) return;
      setLots(result);
      setLotId(result[0]?.id ?? "");
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "Source stock could not be loaded.");
    }).finally(() => { if (active) setLoadingLots(false); });
    return () => { active = false; };
  }, [sourceStoreId]);

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
  if (error && stores.length === 0) return <ErrorState body={error} />;

  return (
    <div className="mx-auto w-full max-w-3xl px-6 py-6">
      <Link to="/inventory" className="text-sm font-medium text-[var(--brand)]">← Stock on hand</Link>
      <h1 className="mt-4 text-lg font-bold text-[var(--text)]">Move stock between sites</h1>
      <p className="mt-1 text-sm text-[var(--text-2)]">Available stock is deducted when dispatched and added to the destination when received.</p>
      <form className="mt-5 space-y-4 rounded-xl border p-5" style={{ borderColor: "var(--border)", background: "var(--surface)" }} onSubmit={(event) => void submit(event)}>
        <label className="block text-sm font-medium">
          Source store
          <select className="eoss-input mt-1 w-full" required value={sourceStoreId} onChange={(event) => { setSourceStoreId(event.target.value); setDestinationStoreId(""); }}>
            {stores.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}
          </select>
        </label>
        <label className="block text-sm font-medium">
          Destination store
          <select className="eoss-input mt-1 w-full" required value={destinationStoreId} onChange={(event) => setDestinationStoreId(event.target.value)}>
            <option value="">Select a different site</option>
            {destinations.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}
          </select>
        </label>
        {loadingLots ? <LoadingState label="Loading source lots" />
          : <label className="block text-sm font-medium">
            Lot
            <select className="eoss-input mt-1 w-full" required value={lotId} onChange={(event) => { setLotId(event.target.value); setQty(""); }}>
              <option value="">Select a lot</option>
              {lots.map((lot) => <option key={lot.id} value={lot.id}>
                {lot.itemCode} · {lot.itemName}{lot.batchRef ? ` · ${lot.batchRef}` : ""} · {lot.availableQty} {lot.uomSymbol} available
              </option>)}
            </select>
          </label>}
        <label className="block text-sm font-medium">
          Quantity {selectedLot && <span className="font-normal text-[var(--text-2)]">(up to {selectedLot.availableQty} {selectedLot.uomSymbol})</span>}
          <input className="eoss-input mt-1 w-full" type="number" min="0.001" max={selectedLot?.availableQty} step="0.001" required value={qty} onChange={(event) => setQty(event.target.value)} />
        </label>
        {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" type="button" onClick={() => navigate("/inventory/transfers")}>Cancel</Button>
          <Button type="submit" disabled={saving || loadingLots || !lots.length}>{saving ? "Dispatching…" : "Dispatch transfer"}</Button>
        </div>
      </form>
    </div>
  );
}
