import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, safeRandomUUID } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import Button from "../components/shared/Button";
import RecordSheet from "../components/shared/RecordSheet";
import { allPages, type Item } from "../inventory/api";
import { listAssets, type Asset } from "../maintenance/api";
import type { RoutingTemplate } from "../production/api";
import { listConfigOptions, type ConfigOption } from "./api";
import { type ChecklistTemplate } from "../quality/api";

type Stage = {
  name: string;
  workCentre?: string | null;
  assetId?: string | null;
  stageConfigOptionId?: string | null;
  inspectionTemplateId?: string | null;
  requiresInspection?: boolean | null;
  isOptional?: boolean | null;
  stageType?: string | null;
  setupTimeMin?: number | null;
  runTimeMin?: number | null;
};
type RouteDetail = Omit<RoutingTemplate, "finishedItemId"> & { finishedItemId: string | null; stages: Stage[] };

const endpoint = "/api/v1/jobs/routing-templates";
const message = (error: unknown) => error instanceof Error ? error.message : "The request failed.";
const itemLabel = (items: Item[], id?: string | null) => {
  if (!id) return "Generic / unassigned";
  const item = items.find((value) => value.id === id);
  return item ? `${item.code} — ${item.name}` : `Product ${id}`;
};

function updateStage(setStages: React.Dispatch<React.SetStateAction<Stage[]>>, index: number, patch: Partial<Stage>) {
  setStages((current) => current.map((stage, i) => i === index ? { ...stage, ...patch } : stage));
}

export default function RoutingTemplatesAdmin() {
  const { user } = useCustomer();
  const canManage = Boolean(user?.roles.some(({ role }) => role === "OWNER" || role === "OPS_MANAGER"));
  const [routes, setRoutes] = useState<RoutingTemplate[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [inspectionTemplates, setInspectionTemplates] = useState<ChecklistTemplate[]>([]);
  const [options, setOptions] = useState<ConfigOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [referencesLoading, setReferencesLoading] = useState(true);
  const [listError, setListError] = useState("");
  const [referenceError, setReferenceError] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [detail, setDetail] = useState<RouteDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [mode, setMode] = useState<"view" | "create" | "revise">("view");
  const [name, setName] = useState("");
  const [product, setProduct] = useState("");
  const [stages, setStages] = useState<Stage[]>([]);
  const [selectedOption, setSelectedOption] = useState("");
  const [saving, setSaving] = useState(false);
  const request = useRef(0);

  async function loadRoutes() {
    setLoading(true);
    try {
      setRoutes(await api<RoutingTemplate[]>(endpoint));
      setListError("");
    } catch (reason) {
      setListError(message(reason));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadRoutes();
    const TRADED = ["traded", "traded_good"];
    const EXCLUDED = ["raw_material", "raw material", "service", "spare_part", "other"];
    Promise.all([
      allPages<Item>("/api/v1/items?status=active"),
      listConfigOptions("PRODUCTION_STAGES"),
      listAssets(false),
      api<ChecklistTemplate[]>("/api/v1/quality/checklist-templates?isActive=true"),
    ])
      .then(([loadedItems, loadedOptions, loadedAssets, loadedTemplates]) => {
        setItems(loadedItems.filter((item) =>
          (item.status ?? "active").toLowerCase() === "active"
          && ["finished_goods", "finished_good"].includes((item.itemType ?? "").toLowerCase())
          && !TRADED.includes((item.productKind ?? "").toLowerCase())
          && !EXCLUDED.includes((item.itemType ?? "").toLowerCase()),
        ));
        setOptions(loadedOptions.filter((option) => option.isActive).sort((a, b) => a.sortOrder - b.sortOrder));
        setAssets(loadedAssets.filter((a) => a.status !== "archived"));
        setInspectionTemplates(loadedTemplates.filter((t) => t.isActive));
      })
      .catch((reason: unknown) => setReferenceError(message(reason)))
      .finally(() => setReferencesLoading(false));
    return () => { request.current += 1; };
  }, []);

  async function inspect(id: string) {
    const generation = ++request.current;
    setDetail(null);
    setMode("view");
    setDetailLoading(true);
    setError("");
    setNotice("");
    try {
      const loaded = await api<RouteDetail>(`${endpoint}/${encodeURIComponent(id)}`);
      if (request.current === generation) setDetail(loaded);
    } catch (reason) {
      if (request.current === generation) setError(message(reason));
    } finally {
      if (request.current === generation) setDetailLoading(false);
    }
  }

  function startCreate() {
    request.current += 1;
    setDetailLoading(false);
    setDetail(null);
    setName("");
    setProduct("");
    setStages([]);
    setSelectedOption("");
    setError("");
    setNotice("");
    setMode("create");
  }

  function startRevision() {
    if (!detail) return;
    setStages(detail.stages.map((stage) => ({ ...stage })));
    setSelectedOption("");
    setError("");
    setNotice("");
    setMode("revise");
  }

  function closeSheet() {
    if (saving) return;
    setMode("view");
    setDetail(null);
    setError("");
  }

  function move(index: number, direction: -1 | 1) {
    setStages((current) => {
      const next = [...current];
      const target = index + direction;
      if (target < 0 || target >= next.length) return current;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!canManage || saving || !stages.length || (mode === "create" && !name.trim()) || (mode === "revise" && !detail)) return;
    setSaving(true);
    setError("");
    try {
      const path = mode === "create" ? endpoint : `${endpoint}/${encodeURIComponent(detail!.id)}/revisions`;
      const body = mode === "create" ? { name: name.trim(), finishedItemId: product || null, stages } : { stages };
      await api(path, { method: "POST", headers: { "Idempotency-Key": safeRandomUUID() }, body: JSON.stringify(body) });
      setMode("view");
      setDetail(null);
      setNotice(mode === "create" ? "Route created." : "Route revision created.");
      await loadRoutes();
    } catch (reason) {
      setError(message(reason));
    } finally {
      setSaving(false);
    }
  }

  const editing = mode !== "view";
  const visibleStages = editing ? stages : detail?.stages ?? [];
  const sheetOpen = editing || !!detail;
  const sheetTitle = mode === "create" ? "Create route" : mode === "revise" ? `Revise ${detail?.name ?? "route"}` : (detail?.name ?? "Route");

  return (
    <div className="space-y-5 px-6 pb-8">
      <div>
        <h2 className="text-sm font-bold text-[var(--text)]">Manufacturing Routes</h2>
        <p className="mt-1 text-sm text-[var(--text-2)]">Configure ordered production stages for a product or a generic route. Editing creates a revision; existing routes cannot be deleted or deactivated here.</p>
        <p className="mt-1 text-sm text-[var(--text-2)]">Manage production stage values in Dropdown &amp; Reference Data.</p>
        {!canManage && <p className="mt-2 text-sm text-[var(--text-2)]">Read only. Owners and operations managers can create routes and revisions.</p>}
      </div>
      {listError && <p role="alert" className="text-sm text-[var(--error)]">Routes could not be loaded: {listError} <button type="button" className="underline" onClick={() => void loadRoutes()}>Retry</button></p>}
      {referenceError && <p role="alert" className="text-sm text-[var(--error)]">Products or production stages could not be loaded: {referenceError}. Editing is unavailable.</p>}
      {notice && <p role="status" className="text-sm text-[var(--text-2)]">{notice}</p>}
      <div className="flex items-center justify-between gap-3">
        <h3 className="font-semibold text-[var(--text)]">Routes</h3>
        {canManage && <Button type="button" disabled={saving || referencesLoading || !!referenceError} onClick={startCreate}>Create route</Button>}
      </div>
      {loading ? <p role="status">Loading routes…</p> : !listError && routes.length === 0 ? <p className="text-sm text-[var(--text-2)]">No routes have been configured.</p> : (
        <div className="overflow-x-auto rounded-xl border" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          <table className="w-full text-left text-sm">
            <thead><tr><th className="px-4 py-3">Route</th><th className="px-4 py-3">Product</th><th className="px-4 py-3">Version</th><th className="px-4 py-3">Stages</th><th className="px-4 py-3"> </th></tr></thead>
            <tbody>{routes.map((route) => <tr key={route.id} className="border-t" style={{ borderColor: "var(--border)" }}>
              <td className="px-4 py-3">{route.name}</td><td className="px-4 py-3">{itemLabel(items, route.finishedItemId)}</td><td className="px-4 py-3">{route.version ?? "—"}</td><td className="px-4 py-3">{route.stageCount}</td>
              <td className="px-4 py-3"><button type="button" className="text-[var(--brand)] underline" disabled={saving} onClick={() => void inspect(route.id)}>Inspect {route.name}</button></td>
            </tr>)}</tbody>
          </table>
        </div>
      )}
      {referencesLoading && <p role="status" className="text-sm">Loading products and production stages…</p>}
      {detailLoading && <p role="status" className="text-sm">Loading route details…</p>}

      {sheetOpen && (
        <RecordSheet
          title={sheetTitle}
          width="lg"
          onClose={closeSheet}
          primary={editing ? { label: saving ? "Saving…" : mode === "create" ? "Create route" : "Create revision", disabled: saving || !stages.length || (mode === "create" && !name.trim()), onClick: () => { void save({ preventDefault: () => {} } as FormEvent); } } : undefined}
          secondary={editing ? { label: "Cancel", onClick: closeSheet } : undefined}
        >
          <form onSubmit={(event) => void save(event)} className="space-y-4">
            {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
            {mode === "create" ? <>
              <label className="block text-sm">Route name<input required className="eoss-input mt-1 w-full" value={name} onChange={(event) => setName(event.target.value)} disabled={saving} /></label>
              <label className="block text-sm">Product<select className="eoss-input mt-1 w-full" value={product} onChange={(event) => setProduct(event.target.value)} disabled={saving}><option value="">Generic / unassigned</option>{items.map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}</select></label>
            </> : detail && <p className="text-sm">Product: {itemLabel(items, detail.finishedItemId)} · Version: {detail.version ?? "—"}</p>}
            <h4 className="font-semibold text-sm">Ordered stages</h4>
            {visibleStages.length === 0 && <p className="text-sm text-[var(--text-2)]">No stages.</p>}
            <ol className="space-y-3">{visibleStages.map((stage, index) => <li key={index} className="rounded-lg border p-3 text-sm" style={{ borderColor: "var(--border)" }}>
              <div className="font-semibold mb-2">{index + 1}. {stage.name}{!stage.stageConfigOptionId && " (legacy stage)"}</div>
              {editing ? (
                <div className="grid grid-cols-2 gap-x-4 gap-y-3">
                  <label className="flex flex-col gap-1 text-xs font-medium text-[var(--text)]">
                    Work centre
                    <input className="eoss-input" value={stage.workCentre ?? ""} disabled={saving} onChange={(e) => updateStage(setStages, index, { workCentre: e.target.value || null })} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs font-medium text-[var(--text)]">
                    Asset
                    <select className="eoss-input" value={stage.assetId ?? ""} disabled={saving} onChange={(e) => updateStage(setStages, index, { assetId: e.target.value || null })}>
                      <option value="">None</option>
                      {assets
                        .filter((a) => !stage.stageConfigOptionId || (a.capabilities ?? []).some((c) => c.stageOptionId === stage.stageConfigOptionId))
                        .map((a) => <option key={a.id} value={a.id}>{a.assetCode} — {a.name}</option>)}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-xs font-medium text-[var(--text)]">
                    Inspection template
                    <select className="eoss-input" value={stage.inspectionTemplateId ?? ""} disabled={saving} onChange={(e) => updateStage(setStages, index, { inspectionTemplateId: e.target.value || null })}>
                      <option value="">None</option>
                      {inspectionTemplates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-xs font-medium text-[var(--text)]">
                    Stage type
                    <select className="eoss-input" value={stage.stageType ?? ""} disabled={saving} onChange={(e) => updateStage(setStages, index, { stageType: e.target.value || null })}>
                      <option value="">Not set</option>
                      <option value="production">Production</option>
                      <option value="inspection">Inspection</option>
                      <option value="setup">Setup</option>
                    </select>
                  </label>
                  <label className="flex flex-col gap-1 text-xs font-medium text-[var(--text)]">
                    Setup time (min)
                    <input type="number" min={0} className="eoss-input" value={stage.setupTimeMin ?? ""} disabled={saving} onChange={(e) => updateStage(setStages, index, { setupTimeMin: e.target.value ? Number(e.target.value) : null })} />
                  </label>
                  <label className="flex flex-col gap-1 text-xs font-medium text-[var(--text)]">
                    Run time (min)
                    <input type="number" min={0} className="eoss-input" value={stage.runTimeMin ?? ""} disabled={saving} onChange={(e) => updateStage(setStages, index, { runTimeMin: e.target.value ? Number(e.target.value) : null })} />
                  </label>
                  <label className="flex items-center gap-2 text-xs font-medium text-[var(--text)] col-span-2">
                    <input type="checkbox" checked={stage.requiresInspection ?? false} disabled={saving} onChange={(e) => updateStage(setStages, index, { requiresInspection: e.target.checked })} />
                    Requires inspection
                  </label>
                  <label className="flex items-center gap-2 text-xs font-medium text-[var(--text)] col-span-2">
                    <input type="checkbox" checked={stage.isOptional ?? false} disabled={saving} onChange={(e) => updateStage(setStages, index, { isOptional: e.target.checked })} />
                    Optional stage
                  </label>
                  <div className="flex gap-2 col-span-2 mt-1">
                    <button type="button" className="btn-ghost rounded px-2 py-1" disabled={saving || index === 0} onClick={() => move(index, -1)} aria-label={`Move stage ${index + 1} up`}>Up</button>
                    <button type="button" className="btn-ghost rounded px-2 py-1" disabled={saving || index === visibleStages.length - 1} onClick={() => move(index, 1)} aria-label={`Move stage ${index + 1} down`}>Down</button>
                    <button type="button" className="btn-ghost rounded px-2 py-1" disabled={saving} onClick={() => setStages((current) => current.filter((_, position) => position !== index))} aria-label={`Remove stage ${index + 1}`}>Remove</button>
                  </div>
                </div>
              ) : (
                <p className="text-xs text-[var(--text-2)]">
                  Work centre: {stage.workCentre || "None"} · Asset: {stage.assetId ? (assets.find((a) => a.id === stage.assetId)?.assetCode ?? stage.assetId) : "None"} · Inspection template: {stage.inspectionTemplateId ? (inspectionTemplates.find((t) => t.id === stage.inspectionTemplateId)?.name ?? stage.inspectionTemplateId) : "None"} · Inspection required: {stage.requiresInspection == null ? "Not set" : stage.requiresInspection ? "Yes" : "No"} · Optional: {stage.isOptional == null ? "Not set" : stage.isOptional ? "Yes" : "No"} · Type: {stage.stageType || "Not set"} · Setup: {stage.setupTimeMin ?? "Not set"} min · Run: {stage.runTimeMin ?? "Not set"} min
                </p>
              )}
            </li>)}</ol>
            {editing && <>
              <div className="flex flex-wrap items-end gap-2">
                <label className="block text-sm">Add production stage
                  <select className="eoss-input mt-1 block" value={selectedOption} disabled={saving} onChange={(event) => setSelectedOption(event.target.value)}>
                    <option value="">Select a stage</option>
                    {options.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
                  </select>
                </label>
                <Button type="button" disabled={saving || !selectedOption} onClick={() => { const option = options.find((item) => item.id === selectedOption); if (option) setStages((current) => [...current, { name: option.label, stageConfigOptionId: option.id }]); setSelectedOption(""); }}>Add stage</Button>
              </div>
              <p className="text-xs text-[var(--text-2)]">Retained stages keep their existing settings, including legacy stages. Removing a stage removes those settings from the new revision.</p>
            </>}
            {!editing && detail && canManage && <Button type="button" disabled={saving || referencesLoading || !!referenceError} onClick={startRevision}>Create revision</Button>}
          </form>
        </RecordSheet>
      )}
    </div>
  );
}
