import { useEffect, useMemo, useState, type FormEvent } from "react";
import { ArrowLeft, Layers, ShieldAlert } from "lucide-react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { api, safeRandomUUID } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import LoadingState from "../components/shared/LoadingState";
import { createLot, createStore, lotFields, storeFields } from "./inlineCreate";

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
  storeId?: string;
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
  // Include quarantine lots in the picker (shown with QC Hold label) but they cannot be
  // transferred directly — the backend rejects them. The UI directs users to Release Lot.
  return rows.filter((lot) =>
    (lot.status === "available" || lot.status === "reserved" || lot.status === "quarantine") &&
    (lot.availableQty > 0 || lot.status === "quarantine")
  );
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
  const [lotId, setLotId] = useState("");
  const [lots, setLots] = useState<Lot[]>([]);
  const [destinationStoreId, setDestinationStoreId] = useState("");
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
    }).catch((reason: unknown) => {
      setStoreError(reason instanceof Error ? reason.message : "Stores could not be loaded.");
    }).finally(() => setLoading(false));
  }

  useEffect(() => { loadStoreList(); }, []);

  const selectedLot = lots.find((lot) => lot.id === lotId);
  const sourceStoreId = selectedLot?.storeId ?? lots[0]?.storeId ?? "";
  const sourceStore = stores.find((store) => store.id === sourceStoreId);

  useEffect(() => {
    if (!lots.length && !loadingLots && stores.length > 0 && lotId === "") {
      const namedSource = stores.find((store) => store.name === requestedFrom);
      const firstStoreId = namedSource?.id ?? stores[0]?.id ?? "";
      if (!firstStoreId) return;
      let active = true;
      setLots([]);
      setLoadingLots(true);
      setLotError("");
      loadLots(firstStoreId).then((result) => {
        if (!active) return;
        const withStore = result.map((lot) => ({ ...lot, storeId: firstStoreId }));
        setLots(withStore);
        const match = withStore.find((lot) => lot.itemCode === requestedMaterial) ?? withStore[0];
        setLotId(match?.id ?? "");
        if (requestedTo) {
          const destination = stores.find((store) => store.name === requestedTo);
          if (destination) setDestinationStoreId(destination.id);
        }
      }).catch((reason: unknown) => {
        if (active) setLotError(reason instanceof Error ? reason.message : "Source stock could not be loaded.");
      }).finally(() => { if (active) setLoadingLots(false); });
      return () => { active = false; };
    }
  }, [stores, lotReload]);

  // Load lots for all stores to build a full lot list (by default load first store, user can browse)
  // When a lot is selected, we know its storeId and can determine the source store
  const destinations = useMemo(
    () => stores.filter((store) => store.id !== sourceStoreId),
    [sourceStoreId, stores],
  );

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
    <div className="p-4 md:p-6 max-w-2xl mx-auto space-y-5">
      <button type="button" onClick={() => navigate(-1)} className="flex items-center gap-1.5 text-sm text-[var(--text-2)] hover:text-[var(--brand)] mb-3">
        <ArrowLeft size={14} /> Inventory
      </button>
      <div className="flex items-center gap-2">
        <Layers size={20} className="text-[var(--brand)]" />
        <h1 className="text-xl font-bold">Stock Transfer</h1>
      </div>
      <p className="text-sm text-[var(--text-2)] mt-1">Available stock is deducted when dispatched and added to the destination when received.</p>

      {sourceStore && selectedLot && (
        <div className="rounded-lg border px-4 py-2 text-sm" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
          <span className="font-semibold">{storeLabel(sourceStore)}</span>
          <span className="mx-2 text-[var(--text-3)]">→</span>
          {destinationStoreId ? <span className="font-semibold">{stores.find((s) => s.id === destinationStoreId) ? storeLabel(stores.find((s) => s.id === destinationStoreId)!) : destinationStoreId}</span> : <span className="text-[var(--text-3)]">Select destination</span>}
        </div>
      )}

      <form className="space-y-4 rounded-xl border p-5" style={{ borderColor: "var(--border)", background: "var(--surface)" }} onSubmit={(event) => void submit(event)}>
        {loadingLots ? <LoadingState label="Loading lots" />
          : <div className="block text-sm font-medium">
            Material Lot *
            <CreatableSelect label="lot" ariaLabel="Material Lot" value={lotId} placeholder="Select a lot" error={lotError} onRetry={() => setLotReload((current) => current + 1)} creatable={canCreateLot} options={lots.map((lot) => ({ id: lot.id, label: lot.status === "quarantine" ? `[QC Hold] ${lot.itemCode} · ${lot.itemName}${(lot.lotNumber || lot.batchRef) ? ` · ${lot.lotNumber || lot.batchRef}` : ""} · ${lot.qty} ${lot.uomSymbol}` : `${lot.itemCode} · ${lot.itemName}${(lot.lotNumber || lot.batchRef) ? ` · ${lot.lotNumber || lot.batchRef}` : ""} · ${lot.availableQty} ${lot.uomSymbol} available` }))} prepare={() => lotFields({ storeId: sourceStoreId })} onChange={(id) => { setLotId(id); setQty(""); }} onCreate={async (values) => {
              const created = await createLot(values, { storeId: sourceStoreId });
              const option = { id: created.id, itemCode: created.itemCode, itemName: created.itemName, batchRef: created.batchRef ?? null, lotNumber: created.lotNumber, qty: created.qty, availableQty: created.availableQty, uomSymbol: created.uomSymbol, status: created.status, storeId: sourceStoreId };
              setLots((current) => [...current.filter((lot) => lot.id !== created.id), option]);
              return { id: created.id, label: `${option.itemCode} · ${option.itemName}` };
            }} />
          </div>}
        {selectedLot?.status === "quarantine" && (
          <div className="rounded-lg border px-4 py-3 text-sm flex gap-2 items-start" style={{ borderColor: "var(--warning)", background: "var(--warning-bg)" }}>
            <ShieldAlert size={15} className="flex-shrink-0 mt-0.5" style={{ color: "var(--warning)" }} />
            <span style={{ color: "var(--text)" }}>
              <strong>QC Hold — transfer not available.</strong> This lot is under incoming quality inspection. To release it to available stock, open the originating GRN and use <em>Release Lot</em> once inspection passes.
            </span>
          </div>
        )}
        <label className="block text-sm font-medium">
          Quantity to dispatch * {selectedLot && selectedLot.status !== "quarantine" && <span className="font-normal text-[var(--text-2)]">(up to {selectedLot.availableQty} {selectedLot.uomSymbol})</span>}
          <input className="eoss-input mt-1 w-full" type="number" min="0.001" max={selectedLot?.availableQty} step="0.001" required value={qty} onChange={(event) => setQty(event.target.value)} />
        </label>
        <div className="block text-sm font-medium">
          Destination Location *
          <CreatableSelect label="store" ariaLabel="Destination Location" value={destinationStoreId} placeholder="Select a store" error={storeError} onRetry={loadStoreList} creatable={canCreateStore} options={destinations.map((store) => ({ id: store.id, label: storeLabel(store) }))} prepare={storeFields} onChange={setDestinationStoreId} onCreate={async (values) => {
            const created = await createStore(values);
            setStores((current) => [...current.filter((store) => store.id !== created.id), created]);
            setDestinationStoreId(created.id);
            return { id: created.id, label: storeLabel(created) };
          }} />
        </div>
        <label className="block text-sm font-medium">
          Transfer Note
          <textarea className="eoss-input mt-1 w-full" rows={2} value={note} onChange={(event) => setNote(event.target.value)} placeholder="Optional reason or reference" />
        </label>
        {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="rounded-lg border px-4 py-2 text-sm" style={{ borderColor: "var(--border)" }} onClick={() => navigate(-1)}>Cancel</button>
          <button type="submit" disabled={saving || loadingLots || !lots.length || selectedLot?.status === "quarantine"} className="rounded-lg px-4 py-2 text-sm font-semibold text-white disabled:opacity-40" style={{ background: "var(--brand-fill)" }}>{saving ? "Dispatching…" : "Dispatch Transfer"}</button>
        </div>
      </form>
    </div>
  );
}
