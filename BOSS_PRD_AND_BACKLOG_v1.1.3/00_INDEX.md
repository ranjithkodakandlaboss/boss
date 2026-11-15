# BOSS PRD and Backlog — Package Index
**Version:** v1.1.3 | **Date:** 15 November 2026 | **MVP Target:** December 20, 2026

> **Index integrity:** Every file listed here exists. No file is listed unless it is physically present in this package. Files marked `MISSING — planned` are explicitly deferred.

---

## Entry Point

| File | Purpose |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Mandatory first read for all agents — rules, stack, naming conventions |

---

## Root Documents

| File | Status | Purpose |
|---|---|---|
| [MASTER_USER_STORY_BACKLOG.csv](MASTER_USER_STORY_BACKLOG.csv) | PRESENT | 98 stories across 18 epics (+4 technical enablers in v1.1.3) |
| [04_FOUNDER_DECISION_REGISTER.md](04_FOUNDER_DECISION_REGISTER.md) | PRESENT | 17 closed decisions; 2 open; SRV/IR terminology unresolved |
| [DEFINITION_OF_DONE.md](DEFINITION_OF_DONE.md) | PRESENT | DoD profiles for BACKEND, FRONTEND, DATA, INFRA |
| [AGENT_OWNERSHIP_AND_PARALLEL_PLAN.md](AGENT_OWNERSHIP_AND_PARALLEL_PLAN.md) | PRESENT | Agent assignments and parallel execution plan |
| [RISK_AND_OPEN_DECISIONS.md](RISK_AND_OPEN_DECISIONS.md) | PRESENT | Open risks and blockers |
| [V1.1.3_CHANGELOG.md](V1.1.3_CHANGELOG.md) | PRESENT | All changes from v1.1.2 → v1.1.3 |
| [V1.1.2_CHANGELOG.md](V1.1.2_CHANGELOG.md) | PRESENT | All changes from v1.1.1 → v1.1.2 (preserved for traceability) |
| [TRACEABILITY_REGISTER.md](TRACEABILITY_REGISTER.md) | PRESENT | 19-finding register mapping audit findings to story IDs |
| [TECHNICAL_CORRECTION_REPORT.md](TECHNICAL_CORRECTION_REPORT.md) | PRESENT | Detailed correction log with before/after |
| [FINAL_VALIDATION_REPORT.md](FINAL_VALIDATION_REPORT.md) | PRESENT | Package integrity check results |

---

## Common Agent Context (11_COMMON_AGENT_CONTEXT/)

| File | Status | Purpose |
|---|---|---|
| [01_PRODUCT_VISION_AND_PRINCIPLES.md](11_COMMON_AGENT_CONTEXT/01_PRODUCT_VISION_AND_PRINCIPLES.md) | PRESENT | ICP, product vision, 7 design principles |
| 02_MASTER_PRODUCT_REQUIREMENTS.md | **MISSING — planned** | Full feature requirements (v1.1 structure only) |
| [03_PRODUCT_TERMINOLOGY_AND_DOMAIN_LANGUAGE.md](11_COMMON_AGENT_CONTEXT/03_PRODUCT_TERMINOLOGY_AND_DOMAIN_LANGUAGE.md) | PRESENT | Canonical domain glossary |
| 04_APPLICATION_EXPERIENCE_AND_DESIGN_SYSTEM.md | **MISSING — planned** | UX patterns and design system |
| [05_SOLUTION_ARCHITECTURE.md](11_COMMON_AGENT_CONTEXT/05_SOLUTION_ARCHITECTURE.md) | PRESENT | Full stack, deployment, patterns |
| [06_DATA_SECURITY_AND_TENANCY_STANDARDS.md](11_COMMON_AGENT_CONTEXT/06_DATA_SECURITY_AND_TENANCY_STANDARDS.md) | PRESENT | RLS, RBAC, never-bypass rules |
| [07_ENGINEERING_STANDARDS_AND_DEFINITION_OF_DONE.md](11_COMMON_AGENT_CONTEXT/07_ENGINEERING_STANDARDS_AND_DEFINITION_OF_DONE.md) | PRESENT | Coding standards, test requirements |
| [08_CROSS_DOMAIN_INTEGRATION_STANDARDS.md](11_COMMON_AGENT_CONTEXT/08_CROSS_DOMAIN_INTEGRATION_STANDARDS.md) | PRESENT | Event contracts, cross-domain patterns |
| [09_AGENT_WORKING_AGREEMENT.md](11_COMMON_AGENT_CONTEXT/09_AGENT_WORKING_AGREEMENT.md) | PRESENT | Agent rules and boundaries |
| [10_COMMON_COMPONENTS_AND_REUSE_CATALOGUE.md](11_COMMON_AGENT_CONTEXT/10_COMMON_COMPONENTS_AND_REUSE_CATALOGUE.md) | PRESENT | Shared services and components |

---

## Data Model (06_DATA_MODEL/)

| File | Status | Purpose |
|---|---|---|
| [physical-schema.md](06_DATA_MODEL/physical-schema.md) | PRESENT | Full 91-table schema with all Flyway migrations V001–V060 |

---

## API Contracts (10_API_CONTRACTS/openapi/)

All 14 OpenAPI YAML files are present. Contracts are authoritative — implementation must match.

| File | Status | Base URL | Note |
|---|---|---|---|
| [identity.yaml](10_API_CONTRACTS/openapi/identity.yaml) | PRESENT | `/api/v1` | |
| [master-data.yaml](10_API_CONTRACTS/openapi/master-data.yaml) | PRESENT | `/api/v1` | |
| [config.yaml](10_API_CONTRACTS/openapi/config.yaml) | PRESENT | `/api/v1` | |
| [inventory.yaml](10_API_CONTRACTS/openapi/inventory.yaml) | PRESENT | `/api/v1` | Includes replenishment + inter-site transfers |
| [sales.yaml](10_API_CONTRACTS/openapi/sales.yaml) | PRESENT | `/api/v1` | Includes frame agreements |
| [procurement.yaml](10_API_CONTRACTS/openapi/procurement.yaml) | PRESENT | `/api/v1` | Includes supplier invitations + performance |
| [production.yaml](10_API_CONTRACTS/openapi/production.yaml) | PRESENT | `/api/v1` | |
| [quality.yaml](10_API_CONTRACTS/openapi/quality.yaml) | PRESENT | `/api/v1` | Also covers Dispatch and POD |
| [dispatch.yaml](10_API_CONTRACTS/openapi/dispatch.yaml) | PRESENT | `/api/v1` | Packing specifications + dispatch packing execution |
| [finance.yaml](10_API_CONTRACTS/openapi/finance.yaml) | PRESENT | `/api/v1` | |
| [whatsapp.yaml](10_API_CONTRACTS/openapi/whatsapp.yaml) | PRESENT | `/api/v1` | |
| [platform-admin.yaml](10_API_CONTRACTS/openapi/platform-admin.yaml) | PRESENT | `/admin/api/v1` | Separate service |
| [maintenance.yaml](10_API_CONTRACTS/openapi/maintenance.yaml) | PRESENT | `/api/v1` | **DEFERRED — EPIC-15 P2** |
| [company-profile.yaml](10_API_CONTRACTS/openapi/company-profile.yaml) | PRESENT | `/api/v1` | **DEFERRED — EPIC-16 P2** |

---

## Configurable Business Values (18_CONFIGURABLE_BUSINESS_VALUES/)

| File | Status | Purpose |
|---|---|---|
| [design.md](18_CONFIGURABLE_BUSINESS_VALUES/design.md) | PRESENT | Full design: 3-tier hierarchy, 30+ categories, routing templates, historical preservation |

---

## Source Documents

| File | Status | Purpose |
|---|---|---|
| [source/customer-meeting-2026-11-15.md](source/customer-meeting-2026-11-15.md) | PRESENT | Raw customer discovery notes |
| [02_CUSTOMER_DISCOVERY_FEEDBACK_NOV15.md](02_CUSTOMER_DISCOVERY_FEEDBACK_NOV15.md) | PRESENT | Processed feedback with classification |
| [01_V1.0_TECHNICAL_AUDIT_AND_CORRECTIONS.md](01_V1.0_TECHNICAL_AUDIT_AND_CORRECTIONS.md) | PRESENT | v1.0 → v1.1 audit report |

---

## Documents Not Present in v1.1.2 (Acknowledged Gaps)

| Document | Note |
|---|---|
| `05_MASTER_PRD.md` | Deferred. |
| `05_DOMAIN_PRDS/` (all domain PRDs) | Deferred. |
| `06_SCOPE_AND_RELEASE_MATRIX.md` | Deferred. |
| `07_PROTOTYPE_PARITY_MATRIX.md` | Deferred. |
| `12_GITHUB_ISSUE_PREPARATION/` | Deferred — not created before Git push approval. |
| `11_COMMON_AGENT_CONTEXT/02_MASTER_PRODUCT_REQUIREMENTS.md` | Planned but not written. |
| `11_COMMON_AGENT_CONTEXT/04_APPLICATION_EXPERIENCE_AND_DESIGN_SYSTEM.md` | Planned but not written. |

**These gaps are explicitly acknowledged, not silently omitted.** Agents must not assume these documents exist.

---

## Package Statistics (v1.1.2)

| Metric | Value |
|---|---|
| Total files | 34 |
| OpenAPI contracts | 14 (12 MVP + 2 P2-desirable in scope) |
| Agent context documents | 8 of 10 (2 planned) |
| DB tables in schema | 91 |
| Stories in backlog | 98 |
| Implementation blockers tracked | 7 |
| Traceability register entries | 19 |
| Open founder decisions | 2 (FD-46, FD-50) |
| Unresolved terminology | 2 (SRV, IR) |
