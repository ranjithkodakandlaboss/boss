# BOSS — Product Vision and Principles
**Version:** v1.1.1 | **Date:** 15 November 2026

---

## What is BOSS?

BOSS (Business Operating Solution System) is a connected Business Operating System purpose-built for **Indian MSME discrete manufacturers**.

It replaces the fragmented combination of Tally, WhatsApp, paper job cards, and Excel spreadsheets that most small manufacturers currently use to run their operations.

BOSS connects sales → procurement → inventory → production → quality → dispatch → finance into a single system of record — built for Indian regulatory requirements, Indian manufacturing vocabulary, and the actual workflows of a 15–150 person factory.

---

## Ideal Customer Profile (ICP)

| Dimension | Range |
|---|---|
| Annual Aggregate Turnover (AATO) | ₹5–50 crore |
| Employees | 15–150 |
| Plants | 1–2 |
| Manufacturing type | Discrete (made-to-order and standard) |
| Industries | Fabrication, engineering components, auto ancillary, machinery, precision machining |
| Current state | Tally for accounts + WhatsApp + paper + Excel for everything else |
| Pain points | No real-time job status, no inventory accuracy, no customer clearance tracking, no job costing |

---

## Pilot Programme

| Fact | Detail |
|---|---|
| Programme | Real operational pilot — not a demo |
| Customers | 3–5 companies (ICP as above), free Jan–Mar 2027 |
| Data | Real data, durable persistence, authenticated, multi-user, auditable |
| Conversion | April 2027: begin converting to paid |
| MVP target | December 20, 2026 build-complete |

---

## Application Surfaces (Pilot Scope)

| Surface | Description |
|---|---|
| **Customer Web App** | Primary interface for all operational users. Full parity with approved prototype. |
| **Shared Platform Foundation** | Identity, tenant, RBAC, audit, events, notifications |
| **Internal Platform Admin** | Minimum-functional: tenant provisioning, user invite, plan entitlements, industry defaults management |
| **WhatsApp Integration** | 4 confirmed workflows via official WhatsApp Business Platform |

**Not in pilot scope:** Dedicated mobile/PWA, service worker, offline queue, React Native/Flutter, Tally-specific export workflow, full general ledger, GST return filing.

---

## Design Principles

### 1. Prototype Parity First
The approved prototype (`visual-experience-transformation` branch of `github.com/ranjithkodakandla/EOSS`) is the primary UX reference. Do not redesign. Do not remove functionality. Do not introduce new navigation patterns unilaterally.

### 2. Indian Manufacturing Vocabulary
Use the canonical terms from `11_COMMON_AGENT_CONTEXT/03_PRODUCT_TERMINOLOGY_AND_DOMAIN_LANGUAGE.md`. Use GRN not "receipt"; use CPO not "sales order"; use NCR not "rejection note". The same vocabulary in code, UI, and documentation.

### 3. Tenant Isolation Without Compromise
Every row of tenant-scoped data is protected by PostgreSQL Row-Level Security. No code path may bypass this. A bug that lets one tenant read another's data is a critical security failure.

### 4. Append-Only Operational Ledgers
Stock movements and audit events are never modified. They accumulate. Historical accuracy is non-negotiable.

### 5. Configurable, Not Hard-Coded
Dropdowns, production stages, inspection types, categories, and other business values must come from the configurable reference-data system. What differs between a fabrication company and a precision machining company must be configuration, not code.

### 6. Contract-First API Development
The OpenAPI contract is written and reviewed before frontend integration begins. Agents do not invent API shapes independently.

### 7. Honest Complexity
If something is blocked, blocked. If something is not done, not done. No invented estimates, no fabricated validation results, no premature FOUNDER-CONFIRMED labels.

---

## What BOSS Is Not (In Pilot)

- Not a full ERP (no general ledger, no payroll, no statutory accounting)
- Not a GST return filing tool
- Not a Tally replacement on day 1 (Tally continues for year-end accounts)
- Not a mobile-first app (responsive web only)
- Not a configurable BPM engine (practical domain-specific configuration only)
- Not a multi-company consolidated reporting tool (single-entity view in pilot)

---

## Regulatory Context

| Requirement | Applicability |
|---|---|
| GST (CGST/SGST/IGST) | All B2B invoices must carry correct GST; supply type determines intra/inter-state |
| E-invoicing (IRN/IRP) | **Mandatory** for B2B invoices issued by businesses with AATO > ₹5 crore. FD-39 must be resolved before EPIC-12 (Finance) completes. |
| GSTIN format | 15-character alphanumeric; validated on entry |
| PAN format | 10-character; validated on entry |
| Invoice numbering | Sequential within financial year per GSTIN; no gaps permitted |
