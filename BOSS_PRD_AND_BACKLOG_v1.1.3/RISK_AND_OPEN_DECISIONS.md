# BOSS — Risk Register and Open Decisions
**Version:** v1.1 | **Date:** 15 November 2026

---

## Open Founder Decisions (Blocking or Near-Blocking)

### FD-55 — Equipment Maintenance in pilot scope?
- **Status:** OPEN
- **Owner:** Ranjith
- **Deadline:** 20 November 2026
- **Question:** Should EPIC-15 (Equipment Maintenance — asset register, PM schedules, work orders) be in scope for the pilot? Customer meeting indicated strong interest (REQ-05).
- **Options:**
  - A. Include in pilot (adds ~20 hrs; push to Jan 20 go-live, not Jan 10)
  - B. Build asset register only (not work orders) — ~8 hrs
  - C. Exclude from pilot; deliver post-Feb 2027
- **If not decided by Nov 20:** EPIC-15 stories will be unowned and unstaffed. Tables are in schema (V041). Schema rollback is not recommended once V041 is applied.

### FD-56 — Company Capability Profile in pilot scope?
- **Status:** OPEN
- **Owner:** Ranjith
- **Deadline:** 20 November 2026
- **Question:** Should EPIC-16 (Company Capability Profile — PDF generation, public link) be in scope for pilot? Customer meeting indicated this helps sales credibility (REQ-10).
- **Options:**
  - A. Include in pilot (~8 hrs; feasible within Jan 10 extended target)
  - B. Exclude from pilot; deliver post-pilot
- **If not decided by Nov 20:** EPIC-16 stories will not be assigned. Schema (V060) is minimal-impact.

### FD-39 — IRN integration with NIC
- **Status:** OPEN (deadline Nov 29)
- **Owner:** Ranjith + CA
- **Question:** Which IRN integration path? Options: direct NIC API, certified GSP, e-invoice service provider.
- **Impact:** If not resolved by Nov 29, BOSS cannot issue legally-compliant B2B invoices for customers above ₹5 cr AATO. This is a legal compliance risk (not just a feature gap).
- **Note:** Gapless sequential invoice numbering is NOT blocked by FD-39. Only the IRN fetch+embed step is blocked.

### FD-45/46 — WABA approval + BSP selection
- **Status:** OPEN (deadline Nov 15 TODAY)
- **Owner:** Ranjith / Rachit
- **Question:** Has WhatsApp Business Account been approved? Which BSP (Business Solution Provider)?
- **Impact:** WhatsApp EPIC-13 cannot begin until WABA is approved and a BSP is selected. If WABA not approved by Nov 25, EPIC-13 will miss Dec 20 target.

---

## Scope Reduction Decision Required

### Capacity Gap: 191 hours P1-MUST shortfall
- **Status:** OPEN — requires founder decision by Nov 20
- **Options:** A (defer P2 + extend to Jan 10), B (extend only), C (add contributor)
- **Recommended:** Option A + B hybrid (defer P2; extend to Jan 10 for P1 completion)
- See `CAPACITY_AND_CRITICAL_PATH.md` for full analysis.

---

## Technical Risks

### RISK-01: Inventory concurrency under load
- **Likelihood:** Medium | **Impact:** High
- **Scenario:** Two simultaneous CPO feasibility checks for the same lot could both pass, then both reserve, leaving the actual stock negative.
- **Mitigation:** Pessimistic `SELECT FOR UPDATE` on `material_lots` within transaction. 10-thread concurrency CI gate (`InventoryConcurrencyTest`) must pass before EPIC-07 is merged.
- **Status:** Mitigation designed; not yet tested.

### RISK-02: PostgreSQL RLS policy gaps
- **Likelihood:** Low | **Impact:** Critical (data leak between tenants)
- **Scenario:** A table added after V051 without an RLS policy returns all rows to all tenants.
- **Mitigation:** `RlsCoverageTest` CI gate checks that every table has an RLS policy. Must be in EPIC-01 CI from day 1.
- **Status:** Test designed in DoD; not yet implemented.

### RISK-03: Invoice sequence gaps under concurrent requests
- **Likelihood:** Medium | **Impact:** High (GST audit risk)
- **Scenario:** Two concurrent invoice creations both read the same sequence counter, produce duplicate invoice numbers.
- **Mitigation:** Pessimistic `SELECT FOR UPDATE` on `invoice_sequence_counters`. `InvoiceSequenceGaplessTest` CI gate.
- **Status:** Mitigation designed; not yet tested.

### RISK-04: Firebase token accepted by platform admin API
- **Likelihood:** Low | **Impact:** Critical (tenant data accessible to other tenants)
- **Scenario:** Customer's Firebase token is used to call a platform admin endpoint that lacks tenant scoping.
- **Mitigation:** `AdminApiCustomerTokenRejectionTest` CI gate; separate Firebase project for admin; `PlatformAdminSecurityConfig` rejects customer tokens.
- **Status:** Test designed; not yet implemented.

### RISK-05: HikariCP dirty connection returned
- **Likelihood:** Low | **Impact:** Critical (tenant context leak)
- **Scenario:** `SET LOCAL app.current_tenant_id` fails; connection returned to pool with wrong tenant context.
- **Mitigation:** Exception in `getConnection()` closes connection (never returns it). `HikariExceptionSafetyTest` CI gate.
- **Status:** Code pattern defined; test designed; not yet implemented.

### RISK-06: WABA approval delay
- **Likelihood:** Medium | **Impact:** Medium
- **Scenario:** Meta WABA approval takes 2–4 weeks. If applied today (Nov 15), approval may not arrive until Dec 1–13.
- **Mitigation:** Apply immediately. Contingency: integrate with BSP's sandbox for development; switch to production WABA post-approval.
- **Status:** Application status unknown (FD-45 OPEN TODAY).

### RISK-07: Pilot customer data quality for opening balances
- **Likelihood:** High | **Impact:** Medium
- **Scenario:** Pilot customer's inventory data is in Excel/paper. Data quality is poor. Migration will take longer than estimated.
- **Mitigation:** EPIC-14 includes a data validation step (V014-S002). Budget 2–3 days for data prep with pilot customer before migration run.
- **Status:** Planned; customer confirmed needed (REQ-11).

### RISK-08: GST rate changes during pilot
- **Likelihood:** Low | **Impact:** Medium
- **Scenario:** GST Council announces rate changes during pilot period. Frontend hardcoded rates break.
- **Mitigation:** HSN-based rates stored in `hsn_codes` table, not hardcoded. UI reads from API.
- **Status:** Schema design correct; API design pending.

---

## Compliance Risks

### COMPLIACE-01: IRN non-compliance for invoices above ₹5 cr AATO
- **Severity:** Legal
- **If FD-39 not resolved by Nov 29:** BOSS cannot issue a compliant B2B invoice to customers above ₹5 cr AATO. It is a legal requirement under GST rules (e-invoicing mandate). The product must not be used for such invoicing until FD-39 is resolved and IRN integration is live.
- **Mitigation in product:** Show warning to user if IRN not registered. Block PDF download of invoice without IRN for customers above threshold.

### COMPLIANCE-02: Synthetic data in dev environment
- **Severity:** Legal (data protection)
- **Rule:** No real pilot customer data in Dev environment (FD-48).
- **Mitigation:** Dev environment uses synthetic data only. CI gate rejects connections to prod Cloud SQL from dev builds.

---

## Dependency Risks

### DEP-01: Testcontainers + PostgreSQL 15 image in CI
- **Risk:** CI runner may not have Docker available or image pull may fail on first run.
- **Mitigation:** Pin specific PostgreSQL 15 image tag. Configure Testcontainers `DOCKER_HOST` for CI.

### DEP-02: Firebase Admin SDK initialization in tests
- **Risk:** Integration tests that need Firebase token verification will fail in CI without credentials.
- **Mitigation:** Use Firebase emulator in test profile. `FirebaseEmulatorConfig` bean activated by Spring profile.

### DEP-03: GCS signed URL generation in tests
- **Risk:** File attachment tests require a GCS bucket.
- **Mitigation:** Use GCS emulator (fake-gcs-server) in test profile.
