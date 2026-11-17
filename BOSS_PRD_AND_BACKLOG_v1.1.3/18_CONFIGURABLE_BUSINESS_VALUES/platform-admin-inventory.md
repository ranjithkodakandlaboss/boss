# Platform Admin master-data discovery inventory

## Scope and sources

This inventory compares the approved configurable-business-values design with the
checked-in Customer App prototype and current backend/API implementation. It is a
source-code inventory, not a query of a live environment.

Reviewed:

- `18_CONFIGURABLE_BUSINESS_VALUES/design.md` (approved category registry and rules)
- `10_API_CONTRACTS/openapi/config.yaml`
- `10_API_CONTRACTS/openapi/master-data.yaml`
- `10_API_CONTRACTS/openapi/quality.yaml`
- `src/customer-app/PROTOTYPE_PARITY.md` and the Customer App forms under
  `src/customer-app/src/sales/proto/`
- `backend/src/main/java/in/boss/shared/config/ConfigController.java`,
  `ConfigService.java`, and `backend/src/main/java/in/boss/masterdata/api/ItemCategoryController.java`
- Migrations `V045_1__config_categories_registry.sql`,
  `V062__industry_templates.sql`, and `V068__platform_core_corrections.sql`

`PROTOTYPE_PARITY.md` identifies the approved Customer App prototype and its copied
Sales components. It explicitly says Platform Admin is a separate application and
was not part of that prototype. No Platform Admin prototype artifact was found in
this repository.

## Summary

- The approved design contains **30 configurable categories**.
- `V045_1` registers **9** categories. Only four of those have industry-template
  option rows: item categories, production stages, inspection types, and rejection
  reasons. `V062` contains **132** such rows across six industries; `V068` copies
  those defaults into tenant-owned storage at provisioning.
- The database codes for the four seeded groups do not match the approved names:
  `PRODUCTION_STAGES` vs `PRODUCTION_STAGE`,
  `INSPECTION_TYPES` vs `INSPECTION_TYPE`, and
  `REJECTION_REASONS` vs `REJECTION_REASON_QUALITY`.
- The existing config API is Customer-authenticated and tenant-context scoped.
  Writes require customer `OWNER`; the special item-category writes also permit
  `OWNER_OR_OPS`. No Platform Admin master-data API or global-value administration
  surface exists.
- No Customer App source calls `/api/v1/config/options`. Current business-value
  dropdowns in the approved Sales prototype include hard-coded Received Via,
  payment-term, inspection-level, and PO-amendment choices. Item, customer, site,
  and UOM selectors instead use tenant records/APIs.
- `config.yaml` describes more operations and response fields than
  `ConfigController` implements. It also names several category codes that disagree
  with both the approved design and current migration codes.

## Complete approved category inventory

“Approved use” is the domain/field identified by the design. “Current values” is
limited to values found in checked-in seed data. “Consumer found” identifies an
actual contract or UI source when one exists; the design domain is not proof that a
working form/API consumer exists.

| # | Configuration group / field | Approved use | Current seeded values | Consumer found / gap | Scope and editability |
|---:|---|---|---|---|---|
| 1 | Inventory / `ITEM_CATEGORY` | Item categories | 30 labels across six industry templates; see Appendix A | Item category API and master-data contract. Customer App item selector uses items but no category selector was found. | Global industry template copied to tenant `item_categories`; tenant may add and deactivate; label edit is not permitted by design. |
| 2 | Inventory / `ITEM_GROUP` | Item groups | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 3 | Inventory / `STOCK_ADJUSTMENT_REASON` | Stock adjustment reasons | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 4 | Sales / `RFQ_TYPE` | RFQ types | None | RFQ screens exist; no RFQ-type field found. Received Via is a separate hard-coded field. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 5 | Sales / `QUOTATION_REJECTION_REASON` | Quotation rejection reasons | None | No rejection-reason selector found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 6 | Sales / `CPO_CANCELLATION_REASON` | Customer PO cancellation reasons | None | No cancellation-reason selector found. PO amendment reasons are distinct. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 7 | Sales / `CUSTOMER_TYPE` | Customer types | None | `master-data.yaml` has `customerTypeId`/`customerTypeLabel`; current customer form has no type selector. Registry uses `CUSTOMER_TYPES`. | Global template copied to tenant; tenant add yes, edit no. |
| 8 | Sales / `CUSTOMER_INDUSTRY` | Customer industry classifications | None | No current form/API consumer found. Distinct from the tenant's system-controlled provisioning industry. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 9 | Procurement / `PR_TYPE` | Purchase requisition types | None | Procurement is listed in the Customer App catalog, but no implemented form consumer was found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 10 | Procurement / `PROCUREMENT_CATEGORY` | Procurement categories | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 11 | Procurement / `SUPPLIER_CATEGORY` | Supplier categories | None | `master-data.yaml` has `supplierCategoryId`/`supplierCategoryLabel`; no current selector found. Registry uses `SUPPLIER_TYPES`. | Global template copied to tenant; tenant add yes, edit no. |
| 12 | Procurement / `REJECTION_REASON_PROCUREMENT` | Procurement rejection reasons | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 13 | Production / `PRODUCTION_STAGE` | Ordered production stage names | 41 labels across six industry templates; see Appendix B | Seeded under `PRODUCTION_STAGES`. Routing-template API is described in the config contract; current Customer App has no option API consumer. | Global industry template copied to tenant; tenant add yes, reorder intended, edit no. |
| 14 | Production / `JOB_TYPE` | Job types | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 15 | Production / `HOLD_REASON` | Job hold reasons | None | Hold/release status exists; no reason selector found. Hold state itself is not master data. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 16 | Production / `REWORK_REASON` | Rework reasons | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 17 | Quality / `INSPECTION_TYPE` | Inspection types | 30 labels across six industry templates; see Appendix C | Seeded under `INSPECTION_TYPES`. RFQ prototype has a hard-coded Inspection Level selector, but it does not consume this category/API. | Global industry template copied to tenant; tenant add yes, edit no. |
| 18 | Quality / `DEFECT_CATEGORY` | Defect categories | None | Quality/NCR screens are cataloged but no current selector was found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 19 | Quality / `REJECTION_REASON_QUALITY` | Quality rejection reasons | 31 labels across six industry templates; see Appendix D | Seeded under `REJECTION_REASONS`; no current reason selector found. | Global industry template copied to tenant; tenant add yes, edit no. |
| 20 | Maintenance / `ASSET_TYPE` | Asset types | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 21 | Maintenance / `MAINTENANCE_CATEGORY` | Maintenance categories | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 22 | Maintenance / `BREAKDOWN_REASON` | Breakdown reasons | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 23 | Maintenance / `FAILURE_CATEGORY` | Failure categories | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 24 | Dispatch / `DISPATCH_METHOD` | Dispatch methods | None | Quality contract names this code, but no current dispatch form/consumer was found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 25 | Dispatch / `PACKING_TYPE` | Packing types | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 26 | Dispatch / `TRANSPORT_CATEGORY` | Transport categories | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 27 | Finance / `PAYMENT_METHOD` | Payment methods | None | Registry has `PAYMENT_MODES`, but no industry values. Customer terms are hard-coded; terms are not payment methods. | Registry says tenant scope. Design says tenant add no, edit no. |
| 28 | Finance / `EXPENSE_CATEGORY` | Expense categories | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 29 | Profile / `FACILITY_TYPE` | Facility types | None | No current facility-type selector found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |
| 30 | Profile / `CAPABILITY_CATEGORY` | Manufacturing capability categories | None | No current form/API consumer found. | Intended global defaults plus tenant copy; tenant add yes, edit no. |

For all 30 categories, the design's hierarchy is: global system defaults maintained
by Platform Admin, industry-template defaults copied at tenant provisioning, and
tenant-specific additions/overrides. Site overrides are future scope. Tenant label
editing is disabled in the category registry; tenant deactivation is supported by
the design only as a historical-preserving operation, not a hard delete. These are
approved design rules, not a description of complete current API behavior. The
design says Platform Admin maintains global defaults but does not define whether
Platform Admin may change a global code, label, order, or active state; no current
Platform Admin API resolves those permissions. Global-value editability is therefore
undecided, not assumed.

`V045_1` currently assigns `scope='tenant'` to `ITEM_CATEGORY`,
`PRODUCTION_STAGES`, `INSPECTION_TYPES`, `REJECTION_REASONS`, `PAYMENT_MODES`,
`SUPPLIER_TYPES`, and `CUSTOMER_TYPES`; `UNIT_TYPES` and `STORE_TYPES` are marked
`system`. The remaining 21 design categories have no registry row. The design also
does not specify category-level exceptions to the global-default/tenant-copy
hierarchy beyond `PAYMENT_METHOD` being non-addable by tenant.

## Existing seeded values

These are the exact code/label pairs in `V062__industry_templates.sql`; each entry
is a tenant seed candidate, not an editable global value. The migration defines
the shared codes once per industry/category; duplicates across industries are
intentional because the resulting tenant copies are isolated.

### Appendix A — `ITEM_CATEGORY` (30 values)

| Industry | Code — label |
|---|---|
| `FABRICATION_STEEL` | `HR_PLATE` — HR Steel Plate / Sheet; `MS_STRUCTURAL` — MS Structural Sections; `SS_SHEET` — Stainless Steel Sheet / Strip; `GI_SHEET` — Galvanised / GI Sheet; `FINISHED_FAB` — Finished Fabrications |
| `PRECISION_MACHINING` | `BAR_STOCK` — Bar Stock / Billets; `CASTING_RM` — Casting Blanks; `FORGING_RM` — Forging Blanks; `CNC_COMPONENT` — CNC Machined Components |
| `ELECTRONICS_ASSEMBLY` | `PCB` — PCBs (Bare); `COMPONENTS` — Electronic Components; `CABLE` — Cables & Harnesses; `ENCLOSURE` — Enclosures / Cabinets; `FINISHED` — Finished Assemblies |
| `FORGING` | `BILLET` — Billets / Ingots; `FORGING_BLANK` — Forging Blanks; `ROUGH_FORGING` — Rough Forgings; `HT_FORGING` — Heat-Treated Forgings; `FINISHED_FRG` — Finished / Machined Forgings |
| `CASTING` | `SCRAP` — Scrap / Returns; `ALLOY_INGOT` — Alloy Ingots / Master Alloys; `SAND` — Sand & Moulding Materials; `ROUGH_CASTING` — Rough Castings; `FINISHED_CST` — Machined / Finished Castings |
| `GENERAL_MANUFACTURING` | `RAW_MATERIAL` — Raw Material; `SUB_COMPONENT` — Sub-Components; `WIP` — Work-in-Progress; `FINISHED` — Finished Goods; `CONSUMABLE` — Consumables; `PACKAGING` — Packaging Material |

### Appendix B — `PRODUCTION_STAGE` (41 values; stored as `PRODUCTION_STAGES`)

| Industry | Code — label, in sort order |
|---|---|
| `FABRICATION_STEEL` | `CUTTING` — Cutting / Shearing; `BENDING` — Bending / Rolling; `FIT_UP` — Fit-Up & Tacking; `WELDING` — MIG / TIG Welding; `GRINDING` — Grinding & Finishing; `SURFACE_TREAT` — Surface Treatment / Painting; `FINAL_INSP` — Final Inspection |
| `PRECISION_MACHINING` | `SAW_CUT` — Sawing / Parting Off; `TURNING` — Turning (Lathe); `MILLING` — Milling (VMC / HMC); `DRILLING` — Drilling / Boring; `GRINDING` — Grinding; `DEBURR` — Deburring & Cleaning |
| `ELECTRONICS_ASSEMBLY` | `SMT` — SMT Placement; `REFLOW` — Reflow Soldering; `THT` — Through-Hole Assembly; `WAVE` — Wave Soldering; `AOI` — AOI Inspection; `FUNC_TEST` — Functional Testing; `BOX_BUILD` — Box Build / Final Assembly |
| `FORGING` | `BILLET_CUT` — Billet Cutting; `HEATING` — Heating & Soaking; `FORGING` — Forging (Press / Hammer); `TRIMMING` — Trimming & Piercing; `SHOT_BLAST` — Shot Blasting; `HEAT_TREAT` — Heat Treatment; `FINAL_INSP` — Final Inspection |
| `CASTING` | `PATTERN` — Pattern Preparation; `MOULD` — Mould Making / Core Setting; `MELTING` — Melting & Alloying; `POURING` — Pouring; `KNOCKOUT` — Knockout & Fettling; `HEAT_TREAT` — Heat Treatment; `MACHINING` — Machining & Dressing |
| `GENERAL_MANUFACTURING` | `RECEIVING` — Receiving & Inspection; `PRE_PROCESS` — Pre-processing; `PRIMARY` — Primary Process; `SUB_ASSEMBLY` — Sub-assembly; `FINAL_ASSEMBLY` — Final Assembly; `TESTING` — Testing & QC; `PACKING` — Packing & Dispatch |

### Appendix C — `INSPECTION_TYPE` (30 values; stored as `INSPECTION_TYPES`)

| Industry | Code — label, in sort order |
|---|---|
| `FABRICATION_STEEL` | `INCOMING` — Incoming Material Inspection; `DIM_CHECK` — Dimensional Check; `WELD_VISUAL` — Weld Visual Inspection; `WELD_NDT` — Weld NDT / UT; `FINAL` — Final Inspection |
| `PRECISION_MACHINING` | `DIMENSIONAL` — Dimensional Inspection; `CMM` — CMM Check; `ROUGHNESS` — Surface Roughness; `HARDNESS` — Hardness Test; `FINAL` — Final Inspection |
| `ELECTRONICS_ASSEMBLY` | `IQC` — Incoming Quality Control; `AOI_INSP` — AOI Inspection; `ICT` — In-Circuit Test; `FUNC_TEST` — Functional Test; `HIPOT` — Hi-Pot Test |
| `FORGING` | `DIMENSIONAL` — Dimensional Inspection; `HARDNESS` — Hardness Test; `UT` — Ultrasonic Flaw Detection; `MPI` — Magnetic Particle Inspection; `FINAL` — Final Inspection |
| `CASTING` | `VISUAL` — Visual Inspection; `DIMENSIONAL` — Dimensional Check; `ULTRA` — Ultrasonic / Radiography; `HARDNESS` — Hardness Test; `FINAL` — Final Inspection |
| `GENERAL_MANUFACTURING` | `INCOMING` — Incoming Quality Check; `IN_PROCESS` — In-Process Inspection; `FINAL` — Final Inspection; `PRE_DISP` — Pre-Dispatch Inspection |

### Appendix D — `REJECTION_REASON_QUALITY` (31 values; stored as `REJECTION_REASONS`)

| Industry | Code — label, in sort order |
|---|---|
| `FABRICATION_STEEL` | `DIM_OOT` — Dimensional Out-of-Tolerance; `WELD_DEFECT` — Weld Defect; `DISTORTION` — Distortion / Warping; `SURFACE` — Surface Defect; `CRACK` — Crack / Fracture |
| `PRECISION_MACHINING` | `OVERSIZE` — Oversize; `UNDERSIZE` — Undersize; `RUNOUT` — Runout / Concentricity; `TOOLMARK` — Tool Marks / Chatter; `THREAD_FAIL` — Thread Failure |
| `ELECTRONICS_ASSEMBLY` | `SOLDER_BRIDGE` — Solder Bridge; `OPEN_JOINT` — Open Joint; `WRONG_COMP` — Wrong Component; `MISSING_COMP` — Missing Component; `FUNC_FAIL` — Functional Failure |
| `FORGING` | `LAPS` — Laps / Folds; `UNDERFILL` — Underfill / Unfill; `CRACK` — Cracks / Voids; `DIM_OOT` — Dimensional Out-of-Tolerance; `SCALE` — Scale / Oxide Inclusions |
| `CASTING` | `POROSITY` — Porosity; `BLOW_HOLE` — Blow Holes; `COLD_SHUT` — Cold Shut; `SHRINKAGE` — Shrinkage Cavity; `DIM_OOT` — Dimensional Out-of-Tolerance; `INCLUSION` — Inclusions / Sand Holes |
| `GENERAL_MANUFACTURING` | `DIMENSIONAL` — Dimensional Failure; `SURFACE` — Surface / Visual Defect; `FUNCTIONAL` — Functional Failure; `MATERIAL` — Wrong / Substandard Material; `PACKAGING` — Packaging Defect |

## Other dropdowns and system-controlled values found

These are not additional rows in the 30-category registry. They are recorded to
avoid silently mapping semantically different values into the nearest category.

| Field | Current source and values | Classification / handling |
|---|---|---|
| RFQ Received Via | `src/customer-app/src/sales/proto/RFQCreate.tsx`: Email, Portal, WhatsApp, Phone, In Person, Post. `RfqWizard.tsx` has the same list. | Hard-coded business values; no approved category/API mapping found. Do not assume these are `RFQ_TYPE`. |
| Payment Terms | `CustomerForm.tsx` and `CustomerDirectory.tsx`: Advance, Net 15, Net 30, Net 45, Net 60. `RFQCreate.tsx`: Net 30, Net 45, Net 60, Net 90, 50% Advance + 50% on Delivery, LC at Sight, Cash on Delivery. The alternate `RfqWizard.tsx` has the latter list. | Hard-coded commercial terms; not equivalent to `PAYMENT_METHOD`. No matching design category or API contract found. |
| Inspection Level | `RFQCreate.tsx`: Level I - Visual only, Level II - Dimensional + Visual, Level III - Full CMM + NDT, Per drawing callout. `RfqWizard.tsx` has Visual, Dimensional, Material certificate, Third party. | Hard-coded values with conflicting lists; not currently wired to `INSPECTION_TYPE`. Requires contract/data-model reconciliation; do not silently merge. |
| PO Amendment Reason | `POAmend.tsx`: qty_change, price_revision, delivery_date, spec_change, add_line, cancel_line, commercial_terms, other. | Hard-coded workflow reasons; no matching category in the approved registry. Not the same as `CPO_CANCELLATION_REASON`. |
| Item Type | `master-data.yaml`: RAW_MATERIAL, CONSUMABLE, FINISHED_GOOD, SERVICE, SPARE_PART, SEMI_FINISHED. | API enum, not a configurable category. Keep system-controlled unless architecture explicitly changes. |
| UOM Type | `master-data.yaml`: quantity, weight, volume, length, area, time, energy, other. | API enum, not a configurable category. UOM records themselves are tenant master data returned by `/uoms`; they are not `/config/options` values. |
| Tenant industry | `design.md` and `V068`: FABRICATION_STEEL, PRECISION_MACHINING, ELECTRONICS_ASSEMBLY, FORGING, CASTING, GENERAL_MANUFACTURING. | System-controlled onboarding/provisioning enum, selected once. Not one of the 30 tenant-editable categories. |
| Tenant roles and business lifecycle/statuses | Role codes and PO/RFQ/quotation/job/invoice/payment state machines are enums or workflow states in the API/prototype. | System-controlled; must not be exposed as editable master data. This includes the statuses explicitly protected by design section 5. |
| Customer, item, site, quotation, and UOM selectors | Customer App fetches tenant records through its customer API; examples include RFQ customer/item/UOM selectors and PO fulfilling-site selection. | Record-backed selectors, not configurable-value categories. Preserve tenant isolation and source APIs. |

`src/customer-app/src/screens/ScreenPage.tsx` currently displays “No options loaded”
for its generic Options field. A source search found no Customer App call to
`/api/v1/config/options`; the approved architecture says dropdowns must use that
API. This is an integration gap, not authorization to add hard-coded replacements.

## API, scope, and implementation gaps

1. **No Platform Admin boundary:** `ConfigController` is under
   `/api/v1/config/options`; `ConfigService` requires `BossContextHolder` with a
   tenant ID. Mutations are annotated `OWNER`; item-category mutations in
   `ItemCategoryController` use `OWNER_OR_OPS`. These are customer-token operations,
   not Platform Admin operations. Do not call them using customer credentials from
   Platform Admin or widen their authority.
2. **No single complete API implementation:** the config contract describes category
   listing, option listing with include-inactive, update, reorder, routing templates,
   and a category/options response envelope. `ConfigController` currently exposes
   only a bare-list GET, POST, and deactivate endpoint. It has no categories,
   PATCH, reorder, includeInactive, or routing-template handlers. The special item
   category endpoint is separately implemented and returns a different bare-list
   shape.
3. **Registry/contract/schema mismatch:** the design's 30 codes, config OpenAPI
   examples, `V045_1` registry codes, and `V062` seed codes do not align. Example:
   design `PRODUCTION_STAGE`, OpenAPI `PRODUCTION_STAGE`, registry/seed
   `PRODUCTION_STAGES`. A migration or new API must not be created until the
   canonical code mapping is approved.
4. **Coverage gap:** 21 approved categories have no registry row; 26 of the
   registered/design categories have no industry-template option rows. Existing
   values outside the four seeded families were not found in migrations. This
   inventory does not imply they should receive invented defaults.
5. **Customer App integration gap:** existing forms contain hard-coded lists and
   do not load options from the config API, despite the architecture rule. Some
   fields with similar labels have incompatible semantics/list contents and need
   a contract-level mapping before replacement.
6. **No Platform Admin UI/API implementation is made by this inventory.** Phase 4
   CRUD is blocked until an approved contract establishes how Platform Admin manages
   global defaults and, if desired, tenant copies without crossing the existing
   customer authentication boundary or creating a second configuration framework.

## Next steps

- Reconcile the 30 design codes against the database registry, migration seed codes,
  and API contract; explicitly classify system enums and non-category dropdowns.
- Approve a Platform Admin-compatible contract for global defaults and the intended
  tenant-specific scope, reusing the existing master-data model.
- Align the Customer App's hard-coded business-value selectors to the agreed
  customer API contract, while preserving historical snapshots and excluding
  system-controlled state machines.
- Only then implement Platform Admin UI/API integration and validate tenant
  isolation, duplicate prevention, deactivation/history behavior, and Customer App
  consumption end to end.
