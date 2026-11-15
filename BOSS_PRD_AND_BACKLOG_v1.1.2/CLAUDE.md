# BOSS — Claude Code Agent Entry Point
**Version:** v1.1.2 | **Date:** 15 November 2026
**Target:** December 20, 2026 MVP build-complete

You are an AI coding agent working on BOSS (Business Operating Solution System) — a connected Business Operating System for Indian MSME discrete manufacturers.

---

## MANDATORY ONBOARDING — Read in this order before writing a single line of code

1. `11_COMMON_AGENT_CONTEXT/01_PRODUCT_VISION_AND_PRINCIPLES.md` — What BOSS is and why
2. `11_COMMON_AGENT_CONTEXT/09_AGENT_WORKING_AGREEMENT.md` — Your rules and boundaries
3. `DEFINITION_OF_DONE.md` — When a story is actually done
4. `11_COMMON_AGENT_CONTEXT/06_DATA_SECURITY_AND_TENANCY_STANDARDS.md` — RLS, RBAC, never bypass
5. `04_FOUNDER_DECISION_REGISTER.md` — Closed decisions you must not reopen
6. `11_COMMON_AGENT_CONTEXT/05_SOLUTION_ARCHITECTURE.md` — Technology stack and patterns
7. `11_COMMON_AGENT_CONTEXT/03_PRODUCT_TERMINOLOGY_AND_DOMAIN_LANGUAGE.md` — Canonical terms
8. Your domain's OpenAPI contract from `10_API_CONTRACTS/openapi/`
9. `06_DATA_MODEL/physical-schema.md` — All tables, FK constraints, RLS
10. Your assigned story in `MASTER_USER_STORY_BACKLOG.csv`

## Context Acknowledgement Required

Before writing implementation code, produce a one-paragraph **Context Acknowledgement** in your first message:
- Which vertical slice and story you are implementing
- Domain you own and what you do NOT own
- Which API contract you are implementing against
- Key data model tables involved and their tenant isolation approach
- Any open decisions that affect your story
- Dependencies that must be DONE before you start

---

## Rules You Cannot Override

1. **Never bypass RLS.** All data access goes through HikariCP → BossContext → `SET LOCAL app.current_tenant_id`. Table name is `tenant_registry`; alias `tenants` is NOT used.
2. **Customer token cannot reach admin APIs.** `AdminApiCustomerTokenRejectionTest` must pass on every admin story.
3. **Outbox-first async.** Never publish directly to Pub/Sub. Write to `outbox_events` in-transaction.
4. **Append-only ledgers.** Never UPDATE or DELETE `stock_movements` or `audit_events`. Add new movements only.
5. **Backend before frontend.** No frontend code for a feature until its backend passes all DoD criteria.
6. **OpenAPI contract before implementation.** The contract in `10_API_CONTRACTS/openapi/` is the agreement. Implement what the contract says. Contract must be reviewed before you code.
7. **No shared package edits without coordination.** `in.boss.shared.*` changes require Ranjith review.
8. **No schema changes in application code.** Use Flyway migrations only: `src/main/resources/db/migration/V{NNN}__description.sql`.
9. **No secrets in code or environment variables.** Secret Manager only.
10. **No hard-coded dropdown lists in frontend.** All options come from the config API (`/api/v1/config/options/{category}`). See `18_CONFIGURABLE_BUSINESS_VALUES/design.md`.
11. **Historical records must preserve original values.** Display labels may change; persisted IDs and original text snapshots never change.

---

## Table Name Convention

| Concept | Table name | Do NOT use |
|---|---|---|
| Tenant workspace | `tenant_registry` | `tenants` |
| Logged-in users | `app_users` | `users` |

This is the actual schema. References to `tenants` or `users` in older docs are v1.0 artefacts.

---

## Configurable Values — Mandatory Pattern

**All dropdowns, selection lists, production stages, inspection templates, and business categories** must come from the configurable reference-data system, not from hard-coded lists.

Before building any screen with a dropdown:
1. Check `18_CONFIGURABLE_BUSINESS_VALUES/design.md` for the category code.
2. Use `GET /api/v1/config/options/{category}` to load options.
3. Store the option `id` (UUID), not the label.
4. Render the current label from the latest config, but store a snapshot label alongside the ID for historical display.

---

## Technology Stack (Closed — Do Not Change)

- Backend: Java 21, Spring Boot 3.x, modular monolith
- Frontend: React 18 + TypeScript + Vite
- Database: PostgreSQL 15 (Cloud SQL), Flyway migrations
- Runtime: GCP Cloud Run
- Auth: Firebase Auth (customer app) / separate Firebase project (platform admin)
- Tests: JUnit 5 + Testcontainers (no H2), Playwright (E2E)
- IaC: OpenTofu

## Package Structure

```
in.boss.{domain}.api         — REST controllers + request/response DTOs
in.boss.{domain}.domain      — Domain interfaces, value objects
in.boss.{domain}.domain.impl — Service implementations
in.boss.{domain}.persistence — JPA entities, repositories
in.boss.{domain}.events      — Outbox event publishers
in.boss.shared.*             — Cross-cutting (security, context, audit, outbox, config) — Ranjith owns
```

## If You Are Stuck

1. Re-read the domain PRD and OpenAPI contract.
2. Check `11_COMMON_AGENT_CONTEXT/08_CROSS_DOMAIN_INTEGRATION_STANDARDS.md` for cross-domain patterns.
3. Check `18_CONFIGURABLE_BUSINESS_VALUES/design.md` for configurable values.
4. If a founder decision is blocking you: record it as OPEN with a recommendation, stop that sub-task, and flag to Ranjith.
5. If your story depends on another story that is not DONE: do NOT start — flag the blocker.
6. Never invent API shapes. The OpenAPI contract is authoritative.
