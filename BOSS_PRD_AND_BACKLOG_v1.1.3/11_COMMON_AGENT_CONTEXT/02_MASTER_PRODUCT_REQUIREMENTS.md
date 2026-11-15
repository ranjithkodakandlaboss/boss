# BOSS — Master Product Requirements
**Version:** v1.1.1 | **Date:** 15 November 2026 | **Status:** PLACEHOLDER — Full document pending

> **Agent note:** This document is a placeholder. The full Master PRD has not been written for v1.1.1. Agents must derive requirements from:
> 1. The OpenAPI contracts in `10_API_CONTRACTS/openapi/` — these are authoritative for API behaviour
> 2. The story acceptance criteria in `MASTER_USER_STORY_BACKLOG.csv`
> 3. `04_FOUNDER_DECISION_REGISTER.md` for closed decisions
> 4. `01_PRODUCT_VISION_AND_PRINCIPLES.md` for product principles

---

## Product Summary

BOSS is a connected Business Operating System for Indian MSME discrete manufacturers (₹5–50 crore AATO, 15–150 employees, 1–2 manufacturing plants).

**Primary user:** Owner-operator of a job-shop or batch-manufacturer in one of these industries:
- Steel Fabrication
- Precision Machining
- Electronics Assembly
- Forging
- Casting
- General Manufacturing

---

## Functional Scope (MVP — December 20, 2026)

BOSS MVP covers the core order-to-cash and procure-to-pay cycles for a discrete manufacturer. The 18 epics span:

| Epic | Domain |
|---|---|
| EPIC-00/01 | Platform infrastructure, tenant provisioning, authentication |
| EPIC-02 | Master data (items, BOM, customers, suppliers) |
| EPIC-03/04 | Inventory (lot tracking, stock movements, reservations) |
| EPIC-05 | Sales (RFQ → Quotation → CPO → Feasibility) |
| EPIC-06 | Procurement (PR → SPO → GRN → 3-way match) |
| EPIC-07 | Production (Job → Routing → Stages → Completion) |
| EPIC-08 | Quality (GRN inspection → Stage-gate → Final → NCR → Concession) |
| EPIC-09 | Dispatch (Clearance cert → Dispatch → POD → Challan) |
| EPIC-10 | Finance — AR (Invoice → GST → IRN → Receipt) |
| EPIC-11 | Finance — AP (Supplier bill → 3-way match → Payment) |
| EPIC-12 | IRN integration (gated on FD-39) |
| EPIC-13 | Reporting and dashboards |
| EPIC-14 | WhatsApp integration (gated on FD-46) |
| EPIC-15 | Assets and maintenance (P2-DESIRABLE) |
| EPIC-16 | Company capability profile (P2-DESIRABLE) |
| EPIC-17 | WhatsApp workflows |
| EPIC-18 | Configurable business values and routing templates |

For detailed requirements per epic, see the story acceptance criteria in `MASTER_USER_STORY_BACKLOG.csv` and the domain OpenAPI contracts.

---

## Key Business Rules

1. **Feasibility before production job creation:** `SELECT FOR UPDATE` on material lots; infeasible jobs cannot be created.
2. **Quality gate before dispatch:** Jobs must have quality status `passed` or `concession_granted` before dispatch creation.
3. **IRN before invoice finalisation for large customers:** B2B invoices to customers with AATO > ₹5 crore require IRN (FD-39).
4. **Sequential invoice numbering:** No gaps. Financial year April–March. Per GSTIN.
5. **All categories from configurable values:** No hard-coded dropdown lists. See `18_CONFIGURABLE_BUSINESS_VALUES/design.md`.
6. **Routing templates versioned:** Template revision creates new version; active jobs retain original.
7. **Historical labels preserved:** Renamed config options still display their original label in historical records.
