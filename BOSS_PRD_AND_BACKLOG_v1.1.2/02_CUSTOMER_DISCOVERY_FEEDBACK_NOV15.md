# Customer Discovery Feedback — 15 November 2026
**Version:** v1.1 | **Date:** 15 November 2026
**Source:** `source/customer-meeting-2026-11-15.md`
**Pilot Customer:** Abhi (fabrication manufacturer — structural steel / precision parts)
**Team:** Ranjith Kumar, Rachit Agrawal, Mohammed Saad Ansari

> **Transcript quality note:** This was an auto-generated transcript with significant garbling. All quoted passages below are verbatim from the transcript. Inferences from garbled sections are marked [INFERRED] and require Ranjith confirmation. No requirements have been invented; only clear signal is recorded.

---

## Meeting Summary

Ranjith walked Abhi through the BOSS prototype covering the end-to-end flow: inquiry → CPO → production → quality → dispatch → invoice → payment. Abhi engaged actively throughout, confirming most modules and raising specific requirements. Post-meeting, Ranjith and Saad discussed outstanding items while Abhi had dropped. Rachit summarised: *"I think we have covered 80 to 90% of your requirement. We can start working on the development."* Abhi: *"Almost."*

Go-live intent confirmed: *"January. First week."* (Rachit, ~01:32)

---

## REQ-01 — Quotation Counter-Offer and Negotiation History

**Status:** CONFIRMED by pilot customer
**Strength:** Explicit, with worked example

**Verbatim quote (~00:57:05):**
> Abhi: "Just to interrupt you. I have quoted around 12,000 per piece. So my customer will send me a counter offer for that price. that will not be the final price. Now they will negotiate also. So they will counter offer me like for example 10,000 and will I have a costing of my raw material so that I can respond to immediately I can go with your order."
> Abhi: "Basically, in my this tab only will I have a calculation of myself also. I can respond to him immediately."

**What is asked for:**
1. Quotation tracks counter-offers from customer with version history
2. When responding to a counter-offer, Abhi wants to see his raw material cost inline in the same tab so he can decide immediately whether the counter-price is viable
3. Final accepted price is recorded as the definitive quotation version

**Design implication:**
- `quotation_negotiation_history` table: each row = one round (our offer, customer counter, notes, accepted_price, round_number)
- Quotation detail page shows current offer + counter + material cost calculation side-by-side
- "Respond to counter-offer" action creates the next negotiation round

**Disposition:** Already in schema (v1.1 V025). Story EPIC-06-S003 exists as P2-DESIRABLE. Pilot customer signal upgrades urgency — recommend **promote to P1-MUST**. Requires FD decision (see REQ Change Register).

---

## REQ-02 — Internal Job Costing (Post-Job, Not on Invoice)

**Status:** CONFIRMED by pilot customer
**Strength:** Explicit; clarified at length by Saad and confirmed by Abhi

**Verbatim quotes (~01:19:48 to 01:23:40):**
> Abhi: "Final costing."
> Saad: "We should keep a running list of it saying that these are the items which are used and these are the cost of it. Then overall we'll just show what is the average per piece cost based on all the consumables used and then we'll keep factor we'll leave the field as blank just what is the labor cost or overhead cost which may be like 10% of whatever raw material on that price or as a lump sum will allow the field to be open field where enter that will give you that overall cost of producing this particular item to PO change invoice because we cannot go back and change the order but we want to know what is the actual cost on the ground."
> Ranjith: "understood whether whether are we really making the profit by delivering the service or are we understood so okay it's neither goes in the invoice or not in the PO but it is that intelligence"
> Abhi: "That will be our internal costing only."
> Abhi: "Not 100% exact but 90% it will be there."

**What is asked for:**
1. After a job is complete, capture actual consumables used (linked to job material issues)
2. Capture labour cost and overhead — either as a percentage of material cost or as a lump-sum entered manually
3. System computes total actual cost per piece / per job
4. This does NOT appear on the customer invoice or CPO — it is internal intelligence only
5. Use case: "Am I actually making profit on repeat orders? Customer asks for discount — can I afford it?"

**Design implication:**
- `job_costing_headers` + `job_costing_labour_entries` + `job_costing_other_expenses` tables already in schema (V041)
- Labour and overhead: open numeric fields (not forced formula)
- Output: cost per piece vs. CPO selling price → margin indicator

**Disposition:** Story EPIC-10-S004 (Job Costing) exists as P2-DESIRABLE. Pilot customer confirmed explicitly. Recommend **promote to P1-MUST** given it is the customer's stated intelligence need.

---

## REQ-03 — Stage-Gate Quality Inspections + Customer Dispatch Clearance Certificate

**Status:** CONFIRMED by pilot customer
**Strength:** Explicit; described with real process context

**Verbatim quote (~00:58:55):**
> Abhi: "Now fabrication. These are the basic five stages which you have mentioned are these are the basic five. material cutting fit up welding painting and dispatch that are the major"
> Abhi (responding to QA discussion): "Now there are actually it depends upon the job size also. If the job size is big, there are some stage inspections also and we have a like QA before painting also and then after painting also and once that is clear, we get a material dispatch clearance certificate from our customer."

**What is asked for:**
1. Production routing has 5 primary stages: cutting → fit-up → welding → painting → dispatch
2. For larger jobs: stage-gate quality inspections at configurable points (e.g. pre-paint, post-paint)
3. Customer must issue a **dispatch clearance certificate** before goods can be dispatched
4. BOSS should capture the clearance certificate receipt and use it as a gate on dispatch creation

**Design implication:**
- `quality_inspections` with `stage_gate` boolean — already in schema
- `customer_clearance_certificates` table — already in schema
- Dispatch creation blocked until clearance certificate attached/confirmed
- Configurable routing (EPIC-10-S002) supports variable stage counts

**Disposition:** Stories EPIC-11-S002 (stage inspections) and EPIC-11-S003 (customer clearance cert) exist as P2-DESIRABLE. Pilot customer described this as standard for larger jobs. Recommend **promote to P1-MUST**.

---

## REQ-04 — Equipment Maintenance (Reactive + Proactive)

**Status:** CONFIRMED by pilot customer — described as important
**Strength:** Explicit; customer used the word "important" and cited production loss

**Verbatim quote (~01:08:41):**
> Ranjith: "maintenance Last service" / "Machine due for service. Machine reactive proactive break."
> Abhi: "Okay. Major issue production loss."
> [INFERRED from context: Abhi confirmed maintenance is important and production loss from breakdown is a real pain point]

**What is asked for:**
1. Track when machines are due for service (scheduled/preventive maintenance)
2. Record reactive maintenance (breakdown → repair)
3. Alert when machine is overdue for service
4. Track time equipment was down (production loss implication)

**Design implication:**
- `assets`, `maintenance_schedules`, `maintenance_work_orders` tables already in schema (V041)
- Alert: dashboard flag when `next_service_due` < today
- Breakdown type: `work_order_type IN ('PREVENTIVE', 'REACTIVE', 'BREAKDOWN')`

**Disposition:** FD-55 is OPEN. Pilot customer confirmed this is important. **Strong recommendation: resolve FD-55 as IN SCOPE.** Stories EPIC-15-S001/S002 should be P2-DESIRABLE (can defer to Jan 10 target but should not be dropped from pilot entirely).

---

## REQ-05 — Packing Specification from Customer

**Status:** CONFIRMED by pilot customer
**Strength:** Explicit; described with packing material variants

**Verbatim quote (~01:08:41 to 01:12:36):**
> Abhi: "Basically basically we get our packing specification from our customer only. for example or that we can use jungle also or if it is it if it will be consumed in their office only factory only it can be sent on pallet also because everything cost because pinewood will be expensive and jungle wood will be cheaper and pallet will be more cheaper they ask some of the material on pallet because it finally adds to cost also."
> Abhi: "Basic packing we are doing ourselves only."

**What is asked for:**
1. Customer specifies packing requirements on CPO (packing type: pinewood crate, pallet, etc.)
2. BOSS captures packing specification and associates it with the dispatch
3. For export jobs: vacuum packing + fumigation may be required (customer scope, not BOSS scope)
4. Basic packing decisions are made by the manufacturer

**Design implication:**
- `packing_specifications` table — already in schema
- CPO has optional `packing_spec_id` reference
- Dispatch shows packing spec for warehouse/packing team

**Disposition:** Story EPIC-17-S002 (packing spec) exists as P2-DESIRABLE. Straightforward feature; recommend retaining as P2.

---

## REQ-06 — Supplier Performance Tracking

**Status:** CONFIRMED by pilot customer (via context)
**Strength:** Inferred from supplier evaluation discussion (~01:01:18 to 01:05:32)

**Verbatim quote (~01:05:32):**
> Ranjith: "based on the price, quantity, deliverable terms and the past history."
> Abhi: "last year delivery."
> Ranjith: "based on the price, quantity, deliverable terms and the past history."

**What is asked for:**
1. When evaluating supplier quotations, compare on: price, delivery performance history, quality record
2. Supplier scorecard: how often did they deliver on time? Any quality rejections?

**Design implication:**
- `supplier_performance_records` table — already in schema
- Supplier quotation comparison view shows historical performance
- Record is updated when: GRN inspection result known, delivery date vs. promised date captured

**Disposition:** Story EPIC-08-S003 (supplier performance) exists as P2-DESIRABLE. Recommend retaining as P2 — useful but not on critical path.

---

## REQ-07 — Consumable Reorder Policies (Independent of Customer Orders)

**Status:** CONFIRMED by pilot customer
**Strength:** Explicit; clear business logic explained

**Verbatim quote (~01:05:32 to 01:08:41):**
> Ranjith: "I don't have to wait for my customer order, right? I have keep some material with me."
> Abhi: "Consumable consumable is irrespective of what order I am receiving on my daily basis on daily basis or whatever stock if it welding it will not depend upon customer order we have to order our new stocks as a girl."

**What is asked for:**
1. Consumables (welding rods, gases, abrasives, PPE) have their own reorder logic independent of CPOs
2. When stock of a consumable falls below minimum level → trigger reorder flag / generate PR automatically
3. This is distinct from make-to-order materials which are procured against specific CPOs

**Design implication:**
- `reorder_policies` table — already in schema
- `item_type IN ('RAW_MATERIAL', 'CONSUMABLE', 'SPARE_PART')` distinguishes reorder behaviour
- Dashboard alert: consumables below reorder point

**Disposition:** Story EPIC-05-S004 (reorder policies) exists as P2-DESIRABLE. Pilot confirmed this is real. Recommend retaining as P2 (straightforward; can be added later).

---

## REQ-08 — Inter-Site Inventory Transfer

**Status:** CONFIRMED by pilot customer
**Strength:** Verbatim "very important"

**Verbatim quote (~01:08:41):**
> Ranjith: "if you want to move the stock from one factory, if you want to move between"
> Rachit: "important especially in case this is very important."

**What is asked for:**
1. Transfer stock between two stores/sites within the same company
2. Movement must be tracked: source site, destination site, items, quantities, authorised by
3. Inventory position at each site must update correctly

**Design implication:**
- `inter_site_transfers` + `inter_site_transfer_lines` — already in schema
- Both source and destination must be within same tenant (RLS enforced)

**Disposition:** Story EPIC-05-S005 (inter-site transfers) exists as P2-DESIRABLE. Pilot confirmed. Recommend retaining as P2.

---

## REQ-09 — Company Capability Profile (PDF Download and Share)

**Status:** CONFIRMED by pilot customer + explicitly named post-meeting by Ranjith
**Strength:** Strong — Ranjith identified it post-meeting as a gap; customer asked for it implicitly

**Verbatim quote (~01:23:40):**
> Abhi: "our complete company history will be with him."
> Ranjith: "Certificate quality certificate."

**Post-meeting discussion (~01:37:47 to 01:39:05):**
> Ranjith: "I think primary two three things company profile I think that we have not accounted for that is one thing he was mentioning about it."
> Ranjith: "So if you have everything, it will generate a document so that they can download and share with anyone and everyone there."
> Saad: "company profile detail specifically facility factory output and I don't know how else do they categorize it total capacity Number of people working, how many areas in terms of echar or square yards it take details"
> Ranjith: "company valuation, turnover, how long they have been doing the work, type of project they have delivered, right? Client."
> Ranjith: "So so if you have everything, it will generate a document so that they can download and share with anyone and everyone there."

**Also (~01:27:43):**
> Abhi: "Send us your company profile."
> [Context: Abhi describes how customers ask for company profile before awarding work]

**What is asked for:**
1. BOSS tenant fills in a structured company profile: capacity, headcount, floor area, years in operation, turnover, types of projects delivered, key clients (optional — Ranjith noted some won't share client names due to competition)
2. Machine assets/certifications can be linked
3. One-click PDF generation for download and sharing with potential customers
4. Possibly: shareable public link (requires FD-56 decision on scope)

**Note on client confidentiality (~01:39:05):**
> Ranjith: "No, no, I know no. Yeah. No one will share the some some cases they will not even share the client name also because So there is a very tough competition runs in this MSM."
> Implication: client name field on company profile must be optional / privacy-controlled.

**Disposition:** FD-56 is OPEN. Post-meeting Ranjith explicitly identified this as a gap. **Strong recommendation: resolve FD-56 as IN SCOPE.** Story EPIC-16-S001 is P2-DESIRABLE; feasible within Jan 10 extended target.

---

## REQ-10 — Incoming Inspection Report (IIR) and Store Receiver Voucher (SRV) Terminology

**Status:** TERMINOLOGY ALIGNMENT (not a new feature — existing GRN flow)
**Strength:** Post-meeting clarification by Saad

**Post-meeting quote (~01:40:34 to 01:43:27):**
> Saad: "then there was IR which was incoming something."
> Ranjith: "incoming in incoming. Inspection review report I guess. IR"
> Saad: "he used something called SRV, store receiver voucher"
> Ranjith: "Stock stock registry I think. Yeah."
> Saad: "I want to ensure that we using the same language here application little n let's keep it generic but if you look at the top navigation bar name N will give that as a description maybe light gray color so that it is not very highlighted but someone wants to read it they know like description"
> Ranjith: "if someone want to look at we can show that as short form also if need be full form if needed"
> Saad: "this is our very first implementation. Just want to keep that as a item that can be to ensure that he gets the reference and it'll sink in easily."

**What is asked for:**
- In the GRN flow, show the industry-standard short forms alongside BOSS terminology:
  - GRN = Store Receiver Voucher (SRV) in customer's language
  - Incoming Quality Inspection = Incoming Inspection Report (IR / IIR) in customer's language
- Display as small gray subtitle text in navigation / page titles
- Not a P1 requirement — P3 / UX polish

**Disposition:** Update `03_PRODUCT_TERMINOLOGY_AND_DOMAIN_LANGUAGE.md` to resolve TERM-SRV and TERM-IR. No new story needed — fold into EPIC-09 frontend story as a UX note.

---

## REQ-11 — Opening Balance Data Migration (Prior Data)

**Status:** CONFIRMED (brief mention)
**Strength:** Rachit: "I want to have the real data" (~01:32)

**What is asked for:**
- Before going live, pilot customer needs opening inventory balances loaded into BOSS
- This is EPIC-14 (Opening Balance Migration) — already in backlog as P1-MUST

**Disposition:** No change needed. EPIC-14 already planned.

---

## REQ-12 — Mobile Web (Responsive, Not Native App)

**Status:** CONFIRMED as the right approach
**Strength:** Ranjith explicitly confirmed this direction; customer didn't push back

**Verbatim quote (~01:46:22 to 01:49:04):**
> Ranjith: "if you use the mobile browser it will automatically render like this for you."
> Ranjith: "without the full-fledged mobile app native app. We'll do a hybrid app but WhatsApp is WhatsApp."
> [Context: showing the web app rendered in mobile browser]

**Disposition:** Confirms FD decision to build responsive web only (no native app). No new requirement.

---

## Meeting Outcome Summary

| Requirement | Customer Signal | Recommended Action |
|---|---|---|
| REQ-01: Quotation negotiation | Explicit, worked example | Promote EPIC-06-S003 to P1-MUST |
| REQ-02: Internal job costing | Explicit, Saad confirmed | Promote EPIC-10-S004 to P1-MUST |
| REQ-03: Stage inspections + clearance cert | Explicit | Promote EPIC-11-S002/S003 to P1-MUST |
| REQ-04: Equipment maintenance | "Major issue", "important" | Resolve FD-55 IN SCOPE; keep P2 |
| REQ-05: Packing specification | Explicit | Retain EPIC-17-S002 as P2 |
| REQ-06: Supplier performance | Implicit | Retain EPIC-08-S003 as P2 |
| REQ-07: Consumable reorder | Explicit | Retain EPIC-05-S004 as P2 |
| REQ-08: Inter-site transfer | "Very important" | Retain EPIC-05-S005 as P2 |
| REQ-09: Company profile PDF | Explicit post-meeting gap | Resolve FD-56 IN SCOPE; keep P2 |
| REQ-10: SRV/IR terminology | UX polish | Update terminology doc; no new story |
| REQ-11: Opening balance migration | Implicit | Already planned (EPIC-14) |
| REQ-12: Mobile web responsive | Confirmed right approach | No change |

**Strongest new finding:** REQ-01, REQ-02, REQ-03 should be promoted from P2 to P1-MUST. These are core workflows the pilot customer explicitly described as essential to their daily operation. **This requires a founder decision (see Change Register).**

**FD-55 and FD-56 recommendations:** Pilot customer confirmed both equipment maintenance (REQ-04) and company profile (REQ-09) as real needs. Both are feasible within Jan 10 extended target. Recommend including both in pilot scope.
