# BOSS — Agent Ownership and Parallel Execution Plan
**Version:** v1.1 | **Date:** 15 November 2026

This document governs which vertical slices run in parallel, which domains each agent owns, and what the hard dependency constraints are.

---

## Agent Ownership Table

| Domain | Agent / Person | Packages Owned | Tables Owned |
|---|---|---|---|
| Shared platform, RLS, Auth, Admin | Ranjith | `in.boss.shared.*`, `in.boss.identity.*`, `in.boss.admin.*` | `tenants`, `users`, `role_assignments`, `audit_events`, `outbox_events`, `idempotency_keys` |
| Inventory | Ranjith | `in.boss.inventory.*` | `items`, `uom`, `stores`, `material_lots`, `stock_movements`, `reorder_policies`, `inter_site_transfers*` |
| Finance | Ranjith | `in.boss.finance.*` | `invoice_sequence_counters`, `invoices`, `invoice_lines`, `receipts`, `receipt_allocations`, `supplier_bills`, `supplier_payments` |
| WhatsApp | Ranjith | `in.boss.whatsapp.*` | `whatsapp_messages`, `whatsapp_workflow_states` |
| Infrastructure, IaC | Ranjith | `infra/`, `src/main/resources/db/migration/` (shared) | (all via Flyway) |
| Master Data, Sales | Saad | `in.boss.masterdata.*`, `in.boss.sales.*` | `customers`, `suppliers`, `items_bom_lines`, `routing_templates*`, `rfqs*`, `quotations*`, `cpos*`, `frame_agreements` |
| Procurement, Receiving | Saad | `in.boss.procurement.*`, `in.boss.receiving.*` | `purchase_requisitions*`, `supplier_quotations*`, `spos*`, `grns*`, `supplier_performance_records` |
| Production | Saad | `in.boss.production.*` | `production_jobs`, `job_stages`, `job_material_requirements*`, `job_material_issues*`, `job_costing*` |
| Quality, Dispatch | Saad | `in.boss.quality.*`, `in.boss.dispatch.*` | `quality_inspections*`, `ncrs*`, `concessions`, `customer_clearance_certificates`, `dispatches*`, `pod_records`, `packing_specifications` |

---

## Parallelism Rules

### What can run in parallel

After VS-02 (RLS + BossContext) is DONE:
- **Ranjith's VS-03** (Auth + RBAC) can run in parallel with **Saad's VS-04** (Master Data)
- **Ranjith's VS-05** (Inventory) can run in parallel with **Saad's VS-06** (Sales) — after both VS-03 and VS-04 are DONE
- **Ranjith's VS-07** (Feasibility) can run in parallel with **Saad's VS-06** (Sales)
- **Saad's VS-08** (Procurement) can run in parallel with **Ranjith's VS-16** (Platform Admin)
- **Ranjith's VS-12** (Finance) can begin as soon as VS-05 and VS-09 are DONE (requires dispatch to exist for invoice creation)

### What cannot run in parallel

| Story | Must Wait For | Reason |
|---|---|---|
| VS-02 (RLS/BossContext) | VS-01 (GCP infra) | No Cloud SQL until infra exists |
| VS-03 (Auth) | VS-02 (RLS) | Firebase filter needs BossContextHolder |
| VS-04 (Master Data) | VS-02 (RLS) | Tables need RLS policies active |
| VS-05 (Inventory) | VS-03 (Auth) | Inventory APIs need RBAC |
| VS-07 (Feasibility) | VS-05 (Inventory) | Feasibility reads material_lots |
| VS-06 (Sales) | VS-04 (Master Data) | CPO references customer + item |
| VS-08 (Procurement) | VS-04 (Master Data) | PR/SPO references supplier + item |
| VS-09 (GRN) | VS-08 (Procurement) | GRN closes SPO lines |
| VS-10 (Production) | VS-07 (Feasibility) + VS-09 (GRN) | Job needs feasibility + stock |
| VS-11 (Quality/Dispatch) | VS-10 (Production) | Dispatch follows production completion |
| VS-12 (Finance) | VS-11 (Dispatch) | Invoice follows dispatch |
| VS-13 (WhatsApp) | VS-03 (Auth) + WABA approval | WABA approval required |

---

## Vertical Slice Dependency Graph

```
VS-01: GCP Infrastructure
  └→ VS-02: RLS + BossContext + Audit + Outbox
       ├→ VS-03: Firebase Auth + RBAC [Ranjith]
       │    ├→ VS-05: Inventory Model [Ranjith]
       │    │    ├→ VS-07: Material Feasibility [Ranjith]
       │    │    │    └→ VS-10: Production (partial gate) [Saad]
       │    │    └→ VS-06: Sales (parallel path) [Saad]
       │    │         └→ VS-08: Procurement [Saad]
       │    │              └→ VS-09: GRN + Receiving [Saad]
       │    │                   ├→ VS-10: Production [Saad]
       │    │                   │    └→ VS-11: Quality + Dispatch [Saad]
       │    │                   │         └→ VS-12: Finance [Ranjith]
       │    └→ VS-13: WhatsApp (needs WABA) [Ranjith]
       │    └→ VS-16: Platform Admin [Ranjith]
       └→ VS-04: Master Data [Saad] (parallel with VS-03)
            ├→ VS-06: Sales [Saad]
            └→ VS-08: Procurement [Saad]

VS-14: Opening Balance Migration [Ranjith] (depends on VS-05 + VS-12)
VS-15: Integration testing + pilot onboarding (depends on all VS-01..VS-14)
```

---

## Shared Resource Conflict Rules

The following situations require coordination before proceeding:

| Shared Resource | Conflict Scenario | Resolution |
|---|---|---|
| `in.boss.shared.*` | Any agent editing shared package | Stop; create coordination note; wait for Ranjith review |
| `V0{NN}__*.sql` files | Two agents both need a migration | Ranjith assigns sequence numbers; agents submit migration content for review |
| `src/frontend/src/api/client.ts` | Generated from OpenAPI; both agents use it | Auto-generated; never manually edited; re-generate after any approved contract change |
| `outbox_events` table | Both agents publish events | Publisher service is shared; event types are domain-specific; no conflict |
| `audit_events` table | Both agents emit audit events | AuditService is shared; call the shared service; never write to table directly |

---

## Git Branch Ownership

| Agent / Person | Branch Prefix | PR Rule |
|---|---|---|
| Ranjith | `feature/vs-01-*`, `feature/vs-02-*`, `feature/vs-03-*`, `feature/vs-05-*`, `feature/vs-07-*`, `feature/vs-12-*`, `feature/vs-13-*`, `feature/vs-14-*`, `feature/vs-16-*` | Ranjith approves own PRs only after Saad review |
| Saad | `feature/vs-04-*`, `feature/vs-06-*`, `feature/vs-08-*`, `feature/vs-09-*`, `feature/vs-10-*`, `feature/vs-11-*` | Ranjith review required for security-touching changes |

---

## Coordination Protocol for Cross-Domain Changes

When Saad's work requires a change to Ranjith's domain (or vice versa):

1. **Document the needed change** in the story comment with exact description
2. **Tag the domain owner** in the note
3. **Do not make the change** yourself — stop that sub-task
4. **Domain owner reviews** and either makes the change or approves it
5. **Continue only after the change is in `main`**

Examples that require this protocol:
- Saad needs a new field on `items` (Ranjith's table)
- Saad needs Ranjith to expose a new Inventory API endpoint for Feasibility
- Ranjith needs Sales domain to publish a new event type

---

## Sprint-Level Parallelism Summary

| Week | Ranjith | Saad | Parallel? |
|---|---|---|---|
| Nov 18–21 | VS-01: GCP + CI/CD + Cloud SQL | — | No (Saad starts Nov 24) |
| Nov 24–25 | VS-02: RLS + BossContext | VS-04: Items + UOM | Yes (after VS-02 day 1 commit) |
| Nov 26–28 | VS-03: Firebase Auth | VS-04: BOM + Routing | Yes |
| Dec 1–3 | VS-05: Inventory lots + reservations | VS-06: RFQ + Quotation | Yes |
| Dec 4–5 | VS-07: Feasibility service | VS-06: CPO lifecycle | Yes |
| Dec 8–10 | VS-16: Platform Admin | VS-08: PR + SPO | Yes |
| Dec 11–12 | VS-12: Finance (invoices) | VS-09: GRN | Yes |
| Dec 15–17 | VS-12: Finance (AR/AP) | VS-10: Production | Yes |
| Dec 18–19 | VS-13: WhatsApp | VS-11: Quality + Dispatch | Yes |
| Dec 20 | VS-14: Opening balance | Integration test | Sequential |
