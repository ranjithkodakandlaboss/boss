# BOSS — Agent Working Agreement
**Version:** v1.1 | **Date:** 15 November 2026
**Authority:** Ranjith (architecture + security review)

This agreement governs how AI coding agents and human engineers collaborate on BOSS. Every person or agent working on BOSS code must read and acknowledge this document before making their first commit.

---

## 1. Onboarding Requirement

Before writing implementation code, produce a **Context Acknowledgement** in your first message, covering:
1. Which vertical slice and story you are implementing
2. Domain you own and what you explicitly do NOT own
3. Which OpenAPI contract you are implementing against
4. Key schema tables involved and their tenant isolation approach
5. Any open decisions (FD-NN) that affect your story
6. All hard dependencies (story IDs) that must be DONE before you start

If you cannot write this acknowledgement, you have not completed onboarding.

---

## 2. Ownership Model

Each vertical slice has one owner. The owner is the ONLY person/agent who may:
- Create or modify Java package code for that domain
- Create or modify React pages/components specific to that domain
- Create domain-specific Flyway migrations

The domain ownership table is in `11_COMMON_AGENT_CONTEXT/02_DOMAIN_OWNERSHIP_AND_BOUNDARIES.md`.

**If your work requires touching a domain you do not own, you MUST coordinate first.** Create a coordination note in the story, describe the needed change, and wait for the domain owner's response before proceeding.

---

## 3. Shared Code — Explicit Coordination Required

The following are shared and may ONLY be modified with Ranjith's review:

| Package / File | Why |
|---|---|
| `in.boss.shared.security.*` | Auth, tenant context, RBAC enforcement |
| `in.boss.shared.audit.*` | Audit event service |
| `in.boss.shared.outbox.*` | Transactional outbox publisher |
| `in.boss.shared.rls.*` | HikariCP RLS override |
| `in.boss.shared.error.*` | Common error codes and responses |
| `src/frontend/src/components/shared/*` | Shared React components |
| `src/frontend/src/api/client.ts` | Generated API client (auto-generated from OpenAPI) |
| Any `V0XX__*.sql` migration file | Schema history is immutable; create new migrations |

**Never edit a committed Flyway migration file.** Create a new migration with the next sequence number.

---

## 4. API Contract Protocol

The OpenAPI contract is the agreement between backend and frontend.

**Contract-first sequence:**
1. Backend agent writes or updates OpenAPI YAML in `10_API_CONTRACTS/openapi/`
2. Ranjith (or domain owner) reviews and approves the contract
3. Frontend TypeScript client is re-generated from the approved contract
4. Backend implements the contract
5. Frontend implements against the generated client

No frontend implementation begins against an unapproved contract.

If you need to change a contract that another agent depends on:
1. Notify all dependents in the story comment
2. Submit contract change for review
3. Wait for approval before changing backend behaviour

---

## 5. What You Must Not Do Without Approval

| Action | Reason | Who to ask |
|---|---|---|
| Change `in.boss.shared.*` | Breaks all tenants | Ranjith |
| Modify another agent's domain package | Causes merge conflicts and lost work | Domain owner |
| Bypass RLS in any query | Data leak risk | Ranjith (never permitted) |
| Hard-code tenant-specific business logic | Breaks multi-tenancy | Ranjith |
| Use H2 in tests | H2 ≠ PostgreSQL; masks real bugs | Ranjith (never permitted) |
| Use `ddl-auto: update` | Uncontrolled schema changes | Never permitted |
| Direct Pub/Sub publish without outbox | No durability guarantee | Ranjith (never permitted) |
| Call another domain's internal service class | Breaks module boundary | Domain owner + Ranjith |
| Publish AI-generated mock data as real pilot data | FD-48 violation | Never permitted |
| Close a founder decision (FD-NN OPEN) | Not in scope for agents | Ranjith |

---

## 6. Cross-Domain Communication Rules

**Allowed:**
- Domain A calls Domain B's REST API (via its OpenAPI contract)
- Domain A consumes Domain B's Pub/Sub event (from outbox relay)
- Domain A reads from a shared view (e.g. `inventory_position_view`)

**Prohibited:**
- Domain A imports and calls `in.boss.domainB.domain.impl.*`
- Domain A directly queries Domain B's tables via SQL
- Domain A emits Pub/Sub events that Domain B relies on without a documented event contract

---

## 7. Git Workflow

- All work on a feature branch: `feature/{vs-number}-{short-description}`
- Rebase on `main` before PR; do not merge `main` into feature branch
- PR requires at least one human reviewer approval
- Security-critical changes require Ranjith's approval specifically
- All 11 CI-gated tests must pass before merge
- All security scan gates must pass before merge
- Squash commits on merge to keep history clean

---

## 8. Definition of Done Reference

A story is DONE only when ALL criteria for its DoD profile are satisfied. See `DEFINITION_OF_DONE.md`.

**Code generated ≠ Done. Code committed ≠ Done. Code deployed ≠ Done.**

Done means: acceptance criteria pass, tests pass, CI gates pass, human reviewer approved, evidence attached.

---

## 9. What to Do When Blocked

If you are blocked by an open founder decision (FD-NN OPEN):
1. Document your blocker in the story comment
2. Identify which FD blocks you
3. Identify the minimum decision needed to unblock
4. Present two options with tradeoffs (do not decide unilaterally)
5. Stop the blocked sub-task and move to other work
6. Never invent a decision to unblock yourself

If you are blocked by a dependency story not yet DONE:
1. Note the blocker
2. Do not start the blocked story
3. Work on other parallel-eligible stories

---

## 10. Architecture Review Triggers

The following changes automatically require Ranjith's review before implementation:

- Any change to multi-tenancy architecture
- Any new index on high-write tables (stock_movements, audit_events)
- Any change to inventory reservation logic
- Any change to invoice sequence numbering
- Any new external integration (IRN, BSP, payment gateway)
- Any change to Firebase Auth configuration
- Any new Pub/Sub topic or Cloud Tasks queue
- Any Platform Admin API endpoint

---

## 11. Communication Protocol

When you are pausing or stopping work, state explicitly:
- "Stopped: blocked on FD-{NN}" — waiting for founder decision
- "Stopped: blocked on {STORY-ID}" — dependency not done
- "Stopped: needs Ranjith review" — security/architecture change flagged
- "Done: DoD satisfied, PR ready for review"

Do not silently stop without a status note.
