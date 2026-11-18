import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { api } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import Tabs from "../components/shared/Tabs";
import { type ProductionJob } from "../production/api";
import {
  loadJobs,
  loadLots,
  loadPositions,
  loadReservations,
  quantity,
  type Item,
  type JobReservation,
  type Lot,
  type Position,
} from "./api";
import { ItemForm } from "./ItemMaster";
import StockNav from "./stockNav";

const TABS = [
  { id: "overview", label: "Overview" },
  { id: "lots", label: "Lots & Locations" },
  { id: "jobs", label: "Jobs" },
];

export default function ItemDetail() {
  const { id = "" } = useParams();
  const [search, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { user } = useCustomer();
  const canWrite = (user?.roles ?? []).some((role) =>
    ["OWNER", "OPS_MANAGER", "STORE_KEEPER", "PURCHASE_MANAGER"].includes(role.role)
  );
  const canDeactivate = (user?.roles ?? []).some((role) =>
    ["OWNER", "OPS_MANAGER"].includes(role.role)
  );
  const activeTab = TABS.some((t) => t.id === search.get("tab"))
    ? search.get("tab")!
    : "overview";

  const [item, setItem] = useState<Item | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [lots, setLots] = useState<Lot[]>([]);
  const [jobs, setJobs] = useState<ProductionJob[]>([]);
  const [reservations, setReservations] = useState<JobReservation[]>([]);
  const [editing, setEditing] = useState(search.get("edit") === "1");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  function load() {
    setLoading(true);
    Promise.all([
      api<Item>(`/api/v1/items/${id}`),
      loadPositions(`?itemId=${id}`),
      loadLots(`itemId=${id}`),
      loadJobs(),
      loadReservations(),
    ])
      .then(([next, stock, itemLots, jobPage, reserved]) => {
        setItem(next);
        setPositions(stock);
        setLots(itemLots);
        setJobs(jobPage);
        setReservations(reserved.filter((row) => row.itemId === id));
      })
      .catch((reason: unknown) =>
        setError(reason instanceof Error ? reason.message : "Inventory item not found.")
      )
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    if (!id) {
      setLoading(false);
      setError("Select an item to view its stock, lots and sources.");
      return;
    }
    load();
  }, [id]);

  if (loading) return <LoadingState label="Loading inventory item" />;
  if (error || !item) return <ErrorState body={error || "Inventory item not found."} />;

  const onHand = positions.reduce((sum, row) => sum + Number(row.totalQty), 0);
  const available = positions.reduce((sum, row) => sum + Number(row.availableQty), 0);
  const reserved = positions.reduce((sum, row) => sum + Number(row.reservedQty), 0);
  const uom = item.uomSymbol || positions[0]?.uomSymbol || lots[0]?.uomSymbol || "";
  const usage = jobs.flatMap((job) =>
    (job.materials ?? [])
      .filter((row) => row.itemId === id)
      .map((row) => ({ job, row }))
  );

  async function deactivate() {
    if (!item) return;
    await api(`/api/v1/items/${item.id}`, { method: "DELETE" });
    load();
  }

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <div className="border-b px-6 py-4" style={{ borderColor: "var(--border)" }}>
        <div className="flex items-start gap-3">
          <button
            type="button"
            onClick={() => navigate("/products")}
            aria-label="Back to item master"
          >
            <ArrowLeft size={16} />
          </button>
          <div className="flex-1">
            <div className="font-mono text-xs font-bold text-[var(--brand)]">{item.code}</div>
            <h1 className="text-xl font-bold">{item.name}</h1>
            <p className="text-sm text-[var(--text-2)]">
              {item.categoryLabel || "No category"} ·{" "}
              {(item.itemType ?? "").replaceAll("_", " ")} ·{" "}
              {item.status === "inactive" ? "Inactive" : "Active"}
            </p>
          </div>
          <Link
            to={`/procurement/pr/new?code=${encodeURIComponent(item.code)}&name=${encodeURIComponent(item.name)}&uom=${encodeURIComponent(uom)}`}
            className="rounded-lg px-3 py-2 text-xs font-semibold text-white"
            style={{ background: "var(--brand-fill)" }}
          >
            Create Purchase Requisition
          </Link>
          {canWrite && (
            <button
              type="button"
              className="text-xs font-semibold"
              onClick={() => setEditing(true)}
            >
              Edit
            </button>
          )}
          {canDeactivate && item.status !== "inactive" && (
            <button
              type="button"
              className="text-xs font-semibold text-[var(--error)]"
              onClick={() => void deactivate()}
            >
              Deactivate
            </button>
          )}
        </div>
        <div className="mt-4 grid gap-3 sm:grid-cols-4">
          <Fact label="On hand" value={quantity(onHand, uom)} />
          <Fact label="Available" value={quantity(available, uom)} />
          <Fact label="Reserved" value={quantity(reserved, uom)} />
          <Fact label="Reorder" value={item.reorderPoint == null ? "—" : quantity(item.reorderPoint, uom)} />
        </div>
      </div>
      <Tabs
        tabs={TABS}
        active={activeTab}
        onChange={(id) => setSearchParams({ tab: id }, { replace: true })}
      />
      <div className="min-h-0 flex-1 overflow-auto p-6">
        {activeTab === "overview" && (
          <div className="max-w-2xl space-y-4">
            <section className="rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
              <h2 className="mb-3 text-sm font-bold">Item details</h2>
              <div className="grid gap-3 sm:grid-cols-2 text-sm">
                <Field label="Code" value={item.code} />
                <Field label="Name" value={item.name} />
                <Field label="Category" value={item.categoryLabel || "—"} />
                <Field label="Type" value={(item.itemType ?? "").replaceAll("_", " ") || "—"} />
                <Field label="Unit of measure" value={uom || "—"} />
                <Field label="Status" value={item.status === "inactive" ? "Inactive" : "Active"} />
                {item.reorderPoint != null && (
                  <Field label="Reorder point" value={quantity(item.reorderPoint, uom)} />
                )}
              </div>
            </section>
          </div>
        )}
        {activeTab === "lots" && (
          <section className="overflow-hidden rounded-xl border" style={{ borderColor: "var(--border)" }}>
            <h2 className="border-b px-4 py-3 text-sm font-bold" style={{ borderColor: "var(--border)" }}>
              Lot and location
            </h2>
            <table className="w-full text-sm">
              <thead>
                <tr>
                  {["Lot", "Heat / batch", "Quantity", "Reserved", "Location", "Status", ""].map(
                    (label) => (
                      <th key={label} className="px-3 py-2 text-left text-xs">
                        {label}
                      </th>
                    )
                  )}
                </tr>
              </thead>
              <tbody>
                {lots.length === 0 && (
                  <tr>
                    <td
                      colSpan={7}
                      className="px-3 py-6 text-center text-sm text-[var(--text-3)]"
                    >
                      No lots recorded for this item.
                    </td>
                  </tr>
                )}
                {lots.map((lot) => (
                  <tr key={lot.id}>
                    <td className="px-3 py-2 font-mono text-xs">
                      {lot.lotNumber || lot.id.slice(0, 8)}
                    </td>
                    <td className="px-3 py-2 text-xs">{lot.batchRef || "—"}</td>
                    <td className="px-3 py-2 font-mono text-xs">
                      {quantity(lot.qty, lot.uomSymbol)}
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">
                      {quantity(lot.reservedQty, lot.uomSymbol)}
                    </td>
                    <td className="px-3 py-2 text-xs">{lot.storeName}</td>
                    <td className="px-3 py-2 text-xs">{lot.status}</td>
                    <td className="px-3 py-2">
                      <Link
                        to={`/inventory/lot/${lot.id}`}
                        className="text-xs font-semibold text-[var(--brand)]"
                      >
                        Open lot
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
        {activeTab === "jobs" && (
          <section className="overflow-hidden rounded-xl border" style={{ borderColor: "var(--border)" }}>
            <h2 className="border-b px-4 py-3 text-sm font-bold" style={{ borderColor: "var(--border)" }}>
              Used by jobs
            </h2>
            <table className="w-full text-sm">
              <thead>
                <tr>
                  {["Job", "Site", "Required", "Reserved", "Issued", "Returned"].map((label) => (
                    <th key={label} className="px-3 py-2 text-left text-xs">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {usage.length === 0 && (
                  <tr>
                    <td
                      colSpan={6}
                      className="px-3 py-6 text-center text-sm text-[var(--text-3)]"
                    >
                      No job allocation or consumption is recorded.
                    </td>
                  </tr>
                )}
                {usage.map(({ job, row }) => {
                  const reservedQty = reservations
                    .filter((entry) => entry.jobId === job.id)
                    .reduce((sum, entry) => sum + Number(entry.reservedQty), 0);
                  return (
                    <tr key={`${job.id}-${row.id}`}>
                      <td className="px-3 py-2">
                        <Link
                          to={`/jobs/${job.id}`}
                          className="text-xs font-semibold text-[var(--brand)]"
                        >
                          {job.jobNumber}
                        </Link>
                      </td>
                      <td className="px-3 py-2 text-xs">{job.siteName}</td>
                      <td className="px-3 py-2 font-mono text-xs">
                        {quantity(row.requiredQty, row.uomSymbol)}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs">
                        {quantity(reservedQty, row.uomSymbol)}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs">
                        {quantity(row.issuedQty, row.uomSymbol)}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs">
                        {quantity(row.returnedQty, row.uomSymbol)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>
        )}
      </div>
      {editing && (
        <ItemForm
          item={item}
          onClose={() => setEditing(false)}
          onSaved={() => {
            setEditing(false);
            load();
          }}
        />
      )}
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div
      className="rounded-lg border p-3"
      style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}
    >
      <div className="text-[11px] text-[var(--text-3)]">{label}</div>
      <div className="mt-1 text-sm font-semibold">{value}</div>
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-xs text-[var(--text-3)]">{label}</div>
      <div className="text-sm">{value}</div>
    </div>
  );
}
