# BOSS — Founder Decision Register
**Version:** v1.1.1 | **Date:** 15 November 2026

> **v1.1.1 correction:** FD-55, FD-56, FD-57 were confirmed interactively by Ranjith in the 15 November 2026 planning session. They are marked FOUNDER-CONFIRMED with that date and the confirmation mechanism noted. SRV and IR abbreviation expansions remain OPEN and unresolvable by agents.

---

## Decision Status Legend

| Status | Meaning |
|---|---|
| FOUNDER-CONFIRMED | Ranjith explicitly confirmed this decision. Date and mechanism recorded. |
| OPEN | Decision not yet made. Agents must stop and flag before proceeding. |
| SUPERSEDED | An earlier decision was revised by a later one. See Notes. |

---

## Closed Decisions

### FD-01 — Multi-tenant SaaS architecture
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
PostgreSQL Row-Level Security with `SET LOCAL app.current_tenant_id`. Single-tenant-per-schema rejected. No cross-tenant queries permitted.

### FD-02 — Modular monolith (not microservices for MVP)
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
Single Spring Boot 3.x deployment. Domain separation enforced by Java package boundaries. Microservices deferred post-MVP.

### FD-03 — Java 21 + Spring Boot 3.x backend
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
Kotlin considered and rejected. No Quarkus/Micronaut. Spring ecosystem lock-in accepted for speed.

### FD-04 — React 18 + TypeScript + Vite frontend
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026

### FD-05 — PostgreSQL 15 on Cloud SQL
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026

### FD-06 — Firebase Auth for customer-facing app
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
Separate Firebase project for platform admin. Customer tokens must be rejected by admin API (enforced by test gate).

### FD-07 — GCP Cloud Run (serverless)
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026

### FD-08 — Testcontainers for all integration tests (no H2)
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
H2 diverges from PostgreSQL behaviour on RLS, triggers, and constraints. All tests use real PostgreSQL.

### FD-09 — OpenTofu (open-source Terraform fork) for IaC
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026

### FD-10 — Transactional Outbox pattern for all async events
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
Direct Pub/Sub publish from service layer is prohibited.

### FD-11 — append-only stock_movements and audit_events
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
DB triggers enforce. No UPDATE or DELETE. This is a correctness and audit requirement.

### FD-12 — Pessimistic locking for inventory reservation
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
`SELECT FOR UPDATE` on `material_lots`. Optimistic locking rejected for this path.

### FD-39 — IRN mandatory for B2B invoices to customers with AATO > ₹5 crore
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
Legal compliance. BOSS must not issue a statutory B2B invoice without IRN to such customers. EPIC-12 integration blocked until IRP API integration is designed. Gate: IRN integration story must be DONE before invoice finalization flow is DONE for large customers.

### FD-45 — WhatsApp integration: deterministic commands only (no AI chatbot)
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
4 workflows confirmed: job status query, stage update, inspection update, photo attachment. AI-driven free conversation rejected for reliability.

### FD-46 — WABA registration and BSP selection
**Status:** OPEN  
BSP not yet selected. Interakt, Wati, and direct Meta API all viable. EPIC-17 stories blocked.

### FD-49 — GCP project structure (dev/staging/prod)
**Status:** FOUNDER-CONFIRMED | **Date:** Early 2026  
Three environments: dev (synthetic data only), staging (pre-production), prod.

### FD-50 — Billing account and cost guardrails
**Status:** OPEN  
Budget alerts and auto-shutdown for dev TBD.

### FD-55 — Maintenance module in MVP scope
**Status:** FOUNDER-CONFIRMED | **Date:** 15 November 2026  
**Confirmation mechanism:** Interactive session AskUserQuestion — Ranjith selected "Yes, confirm FD-55/56/57 all in scope"  
Preventive maintenance schedules and work orders are P2-DESIRABLE for the December 20 MVP. They are in scope; whether they ship in the initial build depends on capacity.

### FD-56 — Company Capability Profile in MVP scope
**Status:** FOUNDER-CONFIRMED | **Date:** 15 November 2026  
**Confirmation mechanism:** Interactive session AskUserQuestion — same as FD-55  
`company_profiles` and `company_profile_publish_config` are P2-DESIRABLE for MVP. EPIC-16 stories are in backlog.

### FD-57 — Tenant onboarding and industry-type seeding approach
**Status:** FOUNDER-CONFIRMED | **Date:** 15 November 2026  
**Confirmation mechanism:** Interactive session AskUserQuestion — same as FD-55  
Platform Admin selects `industry_type` at provisioning. `seed_tenant_defaults()` inserts `is_system = TRUE` rows for item categories, asset type configs, and inspection templates. Tenant admin cannot delete system rows. Required before EPIC-04 (master data) starts.

---

## Open Decisions Blocking Implementation

| ID | Topic | Blocked stories |
|---|---|---|
| FD-46 | WABA / BSP selection | EPIC-17-S001 through EPIC-17-S006 |
| FD-50 | Billing cost guardrails | EPIC-00-S001 (partial) |

---

## Terminology Decisions — Unresolved

The following abbreviations appear in product documentation and have not been confirmed:

| Abbreviation | Candidate A | Candidate B | Status |
|---|---|---|---|
| SRV | Service Request Voucher | Site Receiving Voucher | **OPEN — do not expand until confirmed** |
| IR | Issue Request | Indent Request | **OPEN — do not expand until confirmed** |

**Agent rule:** Use the full phrase "Service/Receiving document" or "Issue/Indent document" in code comments and UI copy until Ranjith confirms the expansion. Do not invent an expansion.

---

## Customer Feedback Classification

> All customer discovery insights are classified as follows. Agents must not treat "Team interpretation" or "Inferred" as confirmed requirements without Ranjith review.

| Classification | Meaning |
|---|---|
| **Explicitly stated** | Customer said this directly in discovery interview |
| **Team interpretation** | Derived from explicit statements; may not be verbatim |
| **Inferred** | Logical inference from context — not stated |
| **Needs clarification** | Customer statement was ambiguous or contradictory |
| **Founder-approved** | Ranjith has reviewed and approved including in product scope |
| **Proposed for approval** | Team recommendation awaiting Ranjith review |

Detailed classification of all customer discovery insights is in `03_CUSTOMER_REQUIREMENT_CHANGE_REGISTER.md`.
