# BOSS v1.1.3 — Implementation Traceability Register
**Date:** 15 November 2026 | **Baseline:** v1.1.3 (correction pass on v1.1.2)

> This register maps every audit finding to an accountable work item. It is not a substitute for updating the stories themselves — every finding below is also reflected in the corresponding story's AcceptanceCriteria and Notes in `MASTER_USER_STORY_BACKLOG.csv`.

---

## Finding Index

| ID | Area | Severity | Finding | Affected Stories | Gate | Status |
|---|---|---|---|---|---|---|
| TR-A1 | Routing schema | Blocker | `inspection_template_id` FK absent from `routing_template_stages` | EPIC-01-S010 (new), EPIC-04-S005, EPIC-11-S002, EPIC-18-S005 | Before backend | Open |
| TR-A2 | Routing schema | Blocker | V047 drops routing refs without data backfill note for in-flight jobs | EPIC-01-S007, EPIC-04-S005, EPIC-10-S002 | Before backend | Open |
| TR-B1 | Config values | Blocker | Duplicate ITEM_CATEGORY source of truth | EPIC-18-S001, EPIC-18-S007 (new), EPIC-04-S001 | Before backend | Open |
| TR-B2 | Config values | High | Missing industry template seed SQL for all 6 industry types | EPIC-18-S003 | Before backend | Open |
| TR-B3 | Config values | High | REORDERED history constraint not enforced; no trigger | EPIC-18-S002, EPIC-01-S008 | Before backend | Open |
| TR-B4 | Config values | High | `tenant_config_option_history` missing append-only DB trigger | EPIC-01-S008 | Before backend | Open |
| TR-B5 | Config values | Medium | `allows_tenant_edit` vs deactivation permission boundary ambiguous | EPIC-18-S002 | Implementation time | Open |
| TR-C1 | API idempotency | Blocker | Mutation POST endpoints lack Idempotency-Key middleware | EPIC-01-S011 (new), all domain backend stories | Before backend | Open |
| TR-C2 | API idempotency | Medium | WhatsApp deduplication exception not documented as a formal exception | EPIC-13-S001 | Implementation time | Open |
| TR-D1 | Audit/security | Blocker | `platform_audit_events` missing append-only trigger | EPIC-01-S008 | Before backend | Open |
| TR-D2 | Security | Blocker | `PLATFORM_ADMIN` in tenant `user_roles.role` CHECK — platform boundary violation | EPIC-02-S006 (new), EPIC-02-S002, V048 migration | Before backend | Open |
| TR-D3 | RLS | High | Complete RLS coverage audit required including new V047_1 table column | EPIC-01-S003 | Before backend | Open |
| TR-E1 | Scope | Medium | FD-55/56 confirmed P2-DESIRABLE but API contracts and story notes said DEFERRED | EPIC-15-S001, EPIC-15-S002, EPIC-16-S001, maintenance.yaml, company-profile.yaml | Housekeeping | Resolved (v1.1.3) |
| TR-E2 | Scope | Medium | EPIC-13 notes reference closed FD-45 as a blocker | EPIC-13-S001 to S005 | Housekeeping | Resolved (v1.1.3) |
| TR-E3 | Dependencies | High | EPIC-10-S002 missing dependency on EPIC-04-S005 | EPIC-10-S002 | Before backend | Resolved (v1.1.3) |
| TR-E4 | Dependencies | Medium | EPIC-11-S002 missing dependency on EPIC-04-S005 | EPIC-11-S002 | Before backend | Resolved (v1.1.3) |
| TR-E5 | Dependencies | Medium | EPIC-18-S005 missing dependency on EPIC-01-S010 | EPIC-18-S005 | Before backend | Resolved (v1.1.3) |
| TR-F1 | Housekeeping | Low | Physical schema header version still v1.1.2 | physical-schema.md | Housekeeping | Open |
| TR-F2 | Housekeeping | Low | CBV design version still v1.1.1 | 18_CONFIGURABLE_BUSINESS_VALUES/design.md | Housekeeping | Open |
| TR-F3 | Housekeeping | Low | Missing documents (02 and 04 in common context) acknowledged but not assigned | EPIC-00 or standalone | Housekeeping | Accepted |

---

## Detailed Finding Records

### TR-A1 — inspection_template_id FK absent from routing_template_stages

**Severity:** Blocker (must exist before EPIC-04-S005 and EPIC-11-S002 start)

**Finding:** The CBV design (section 4.3) specifies `ALTER TABLE routing_template_stages ADD COLUMN inspection_template_id UUID REFERENCES inspection_parameter_templates(id)` in a migration labelled `V047a`. `V047a` is not a valid Flyway version identifier. The column was absent from the V047 DDL delivered in v1.1.2, leaving the routing→inspection link broken at the database level.

**Required correction:**
- V047_1 migration added to physical-schema.md (v1.1.3)
- CBV design updated to reference V047_1 (v1.1.3)
- New story EPIC-01-S010 owns this migration
- EPIC-04-S005, EPIC-11-S002, EPIC-18-S005 updated with this dependency

**Validation evidence:** Flyway migration test from V001 must include V047_1; integration test: routing stage with and without inspection_template_id.

---

### TR-A2 — V047 drops routing refs without data backfill note for in-flight jobs

**Severity:** Blocker for production routing implementation

**Finding:** V047 drops `job_stages.routing_op_id` and `production_jobs.routing_id` without specifying how in-flight production jobs are migrated. For a new installation (no live data at migration time), this is safe. For a production migration, existing jobs would lose their stage associations.

**Required correction:** EPIC-01-S007 and EPIC-01-S010 acceptance criteria note: "V047 DROP COLUMN statements are safe for fresh installations. For production upgrade from a prior schema version, a data backfill script must run before V047 to copy job_stage data to the new columns. This backfill is NOT yet written — it is out of scope for the initial pilot (no prior production data exists)."

**Validation evidence:** EPIC-01-S007 integration test covers V001→current clean migration. Opening balance migration story (EPIC-14) handles historical data.

---

### TR-B1 — Duplicate ITEM_CATEGORY source of truth

**Severity:** Blocker (must resolve before EPIC-04-S001 and EPIC-18-S002 start)

**Finding:** `item_categories` (a full dedicated table with hierarchical `parent_id` structure, V045) coexists with `ITEM_CATEGORY` as a category code entry in `config_categories`. The `items` table FK (`items.category_id`) references `item_categories` directly, not `tenant_config_options`. If both are used independently, item categories become inconsistent.

**Required correction:**
- `item_categories` table is the **authoritative DDL-level store** for item category hierarchy
- `ITEM_CATEGORY` entry in `config_categories` is **metadata only** — it marks the category as configurable and routes `GET /config/options/ITEM_CATEGORY` to read from `item_categories`, not from `tenant_config_options`
- No `tenant_config_options` rows should be created for `category_code = ITEM_CATEGORY`
- New story EPIC-18-S007 delivers this resolution
- CBV design table updated with explicit note (v1.1.3)

**Validation evidence:** Integration test: zero rows in `tenant_config_options` for `category_code = ITEM_CATEGORY` after tenant provisioning; `GET /config/options/ITEM_CATEGORY` returns `item_categories` rows.

---

### TR-B2 — Missing industry template seed SQL

**Severity:** High (blocks EPIC-04-S001 — tenant provisioning creates no default master data without seeds)

**Finding:** `seed_tenant_defaults(tenant_id UUID, industry_type TEXT)` is referenced in CBV design and EPIC-18-S003, but the actual seed rows (what options get inserted per industry type for each of the 30+ category codes) are not defined anywhere in the package.

**Required correction:** EPIC-18-S003 must deliver actual seed SQL files committed to `src/main/resources/db/seed/` for all 6 industry types. The story AC has been updated to require this.

**Validation evidence:** Integration test: provision a `FABRICATION_STEEL` tenant; verify `PRODUCTION_STAGE` options include at least: Cutting, Fit-up, Welding, Painting, Final Inspection.

---

### TR-B3 — REORDERED history constraint not enforced

**Severity:** High

**Finding:** `tenant_config_option_history.change_type` CHECK includes `REORDERED` but no trigger, service rule, or test requires the service to write a `REORDERED` event when `sort_order` changes on a `tenant_config_options` row.

**Required correction:** EPIC-18-S002 service implementation must write `REORDERED` event to history when `sort_order` changes. AC updated. EPIC-01-S008 must ensure the append-only trigger exists so history rows cannot be retroactively removed.

**Validation evidence:** Integration test: change `sort_order` on two config options; verify REORDERED events appear in `tenant_config_option_history`.

---

### TR-B4 — tenant_config_option_history missing append-only trigger

**Severity:** High (security gap — config change audit trail could be edited)

**Finding:** The CBV design says `tenant_config_option_history` is append-only, but V050 in v1.1.2 only defined triggers for `stock_movements` and `audit_events`. The history table had no DB-level protection.

**Required correction:** V050 in physical-schema.md extended with append-only trigger for `tenant_config_option_history` (v1.1.3). EPIC-01-S008 AC updated.

**Validation evidence:** DB-level test: attempt UPDATE on `tenant_config_option_history` row raises exception.

---

### TR-C1 — Mutation POST endpoints lack Idempotency-Key middleware

**Severity:** Blocker (required before domain backends ship to staging)

**Finding:** All POST mutation endpoints must accept `Idempotency-Key` header per architecture standards. While the final validation report claimed PASS on "representative contracts," no shared platform middleware existed to enforce this. Duplicate requests (network retries, frontend bugs) could create duplicate invoices, production jobs, etc.

**Required correction:**
- New story EPIC-01-S011 delivers shared `IdempotencyKeyFilter` Spring component
- Domain backend stories EPIC-05-S001, EPIC-06-S001, EPIC-08-S001, EPIC-10-S001, EPIC-11-S001, EPIC-12-S001 updated with dependency on EPIC-01-S011
- WhatsApp exception: `X-WhatsApp-Message-ID` deduplication used instead of `Idempotency-Key`
- Config GET endpoints are exempt (reads are idempotent by definition)

**Validation evidence:** Integration test: duplicate POST with same `Idempotency-Key` returns 200 with same response body, no duplicate record created.

---

### TR-D1 — platform_audit_events missing append-only trigger

**Severity:** Blocker (security/audit integrity)

**Finding:** V044 DDL comment states "Append-only: separate trigger blocks UPDATE/DELETE on platform_audit_events" but this trigger was absent from V050 in v1.1.2.

**Required correction:** V050 extended with `trg_platform_audit_events_append_only` trigger (v1.1.3). EPIC-01-S008 AC updated.

**Validation evidence:** DB-level test: attempt UPDATE on `platform_audit_events` row raises exception.

---

### TR-D2 — PLATFORM_ADMIN in tenant user_roles CHECK — platform boundary violation

**Severity:** Blocker (security)

**Finding:** `user_roles.role` CHECK constraint (V006 DDL) includes `PLATFORM_ADMIN`. `user_roles` is a tenant-scoped table (has `tenant_id FK`). `PLATFORM_ADMIN` is a platform-level concept managed in `platform_admin_users`. Including it as a valid tenant role creates a potential path where a customer Firebase token + a `PLATFORM_ADMIN` `user_roles` row could be used to bypass platform API boundary checks.

**Required correction:**
- V048 migration added to physical-schema.md: drops and recreates `user_roles.role` CHECK excluding `PLATFORM_ADMIN` (v1.1.3)
- New story EPIC-02-S006 owns this migration and security test
- EPIC-02-S002 updated with dependency on EPIC-02-S006

**Validation evidence:** Security test: `AdminApiCustomerTokenRejectionTest` extended to verify inserting `PLATFORM_ADMIN` into `user_roles` fails; customer token + `OWNER` role cannot access `/admin/api/v1`.

---

### TR-D3 — Complete RLS coverage audit

**Severity:** High

**Finding:** V051 RLS array was updated in v1.1.2 but the new column `routing_template_stages.inspection_template_id` (from V047_1) is a FK, not a new table — no RLS impact. Confirm all tables with `tenant_id` are in V051.

**Required correction:** EPIC-01-S003 AC updated to list the complete V051 table array and confirm `platform_admin_users` / `platform_audit_events` exclusion is intentional. No new tables needed.

**Validation evidence:** SQL query: `SELECT tablename FROM pg_tables WHERE schemaname='public'` minus tables that intentionally have no RLS (hsn_codes, config_categories, platform_admin_users, platform_audit_events). Confirm result matches V051 array exactly.

---

### TR-E1 — FD-55/56 scope notes corrected (Resolved)

**Status:** Resolved in v1.1.3

maintenance.yaml, company-profile.yaml, EPIC-15-S001, EPIC-15-S002, EPIC-16-S001 updated from "DEFERRED from MVP" to "P2-DESIRABLE — FOUNDER-CONFIRMED — in MVP scope." FD-55 and FD-56 are closed decisions.

---

### TR-E2 — EPIC-13 FD-45 reference corrected (Resolved)

**Status:** Resolved in v1.1.3

EPIC-13-S001 through S005 notes changed from "Blocked: FD-45/46" to "Blocked: FD-46 only (FD-45 CLOSED)". FD-45 is a closed founder decision: deterministic WhatsApp commands confirmed.

---

### TR-E3/E4/E5 — Dependency corrections (Resolved)

**Status:** Resolved in v1.1.3

- EPIC-10-S002 Dependencies: added EPIC-04-S005
- EPIC-11-S002 Dependencies: added EPIC-04-S005
- EPIC-18-S005 Dependencies: added EPIC-01-S010

---

## True Implementation Blockers (must resolve before domain backend starts)

| Priority | Story | Blocker | Unblocked by |
|---|---|---|---|
| 1 | EPIC-01-S008 | Append-only triggers for config history + platform audit | Flyway test from V001 |
| 2 | EPIC-01-S010 | V047_1: inspection_template_id FK on routing_template_stages | — |
| 3 | EPIC-01-S011 | Idempotency-Key middleware | EPIC-01-S006 |
| 4 | EPIC-02-S006 | Remove PLATFORM_ADMIN from user_roles CHECK (V048) | EPIC-01-S007 |
| 5 | EPIC-18-S001 | Config categories migration with ITEM_CATEGORY clarification | — |
| 6 | EPIC-18-S003 | Industry template seed SQL for all 6 industry types | EPIC-18-S001 |
| 7 | EPIC-18-S007 | Resolve ITEM_CATEGORY dual-source | EPIC-18-S001 |

**Rule:** No domain story (EPIC-04 onwards) may start until items 5, 6, 7 are DONE. Items 1–4 must be DONE before EPIC-10, EPIC-11, EPIC-13 start. EPIC-01-S011 must be DONE before any domain POST mutation endpoint is integrated to staging.

---

## Issues Handled During Implementation

| Story | Issue | When to resolve |
|---|---|---|
| EPIC-18-S002 | REORDERED event written to history on sort_order change | During EPIC-18-S002 service implementation |
| EPIC-18-S002 | allows_tenant_edit vs deactivation boundary clarified in service | During implementation |
| EPIC-13-S001 | WhatsApp X-Message-ID deduplication exception documented | During EPIC-13-S001 implementation |
| All domain backend | Verify Idempotency-Key applied to every POST endpoint | During PR review for each domain story |
| EPIC-01-S007 | V047 backfill note for in-flight jobs documented (not applicable to initial pilot) | During EPIC-01-S007 implementation |

---

## Scope, Architecture and Target Confirmation

| Item | Status |
|---|---|
| December 20, 2026 MVP target | **UNCHANGED** |
| Approved architecture (Java 21, Spring Boot 3.x, React 18, PostgreSQL 15, Cloud Run) | **UNCHANGED** |
| FD-55 Maintenance in scope (P2-DESIRABLE) | **CONFIRMED** |
| FD-56 Company Capability Profile in scope (P2-DESIRABLE) | **CONFIRMED** |
| FD-57 Tenant onboarding and industry-type seeding | **CONFIRMED** |
| FD-46 WABA/BSP selection (EPIC-13 blocked) | **OPEN — no change** |
| FD-50 Billing guardrails (EPIC-00-S001 partial block) | **OPEN — no change** |
| Existing 94 stories scope | **UNCHANGED** |
| 4 new technical enabler stories added | EPIC-01-S010, EPIC-01-S011, EPIC-02-S006, EPIC-18-S007 |
| Total stories in v1.1.3 | **98** |

No product features have been added, removed, or redesigned. All 4 new stories are technical corrections and platform enablers required to deliver the existing scope correctly.
