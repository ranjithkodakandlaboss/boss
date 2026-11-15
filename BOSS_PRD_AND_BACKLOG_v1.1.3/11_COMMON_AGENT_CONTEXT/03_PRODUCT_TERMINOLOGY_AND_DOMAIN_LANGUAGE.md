# BOSS — Product Terminology and Domain Language
**Version:** v1.1 | **Date:** 15 November 2026

Use these terms consistently in code (class names, method names, DB columns), UI copy, PRDs, and agent communication. Deviating from canonical terms causes confusion and inconsistent domain models.

---

## Core Concepts

| Term | Definition | Do NOT use |
|---|---|---|
| **Customer Group** | The highest entity — an account that holds one or more BOSS tenants. E.g. "Acme Group" with two factories. | "Account", "Organisation" |
| **BOSS Tenant** | One operational workspace in BOSS. Corresponds to one manufacturing business unit or brand. | "Company", "Organisation", "Client" |
| **Legal Entity** | A registered legal entity (company, LLP, proprietorship) under a tenant. Has PAN. | "Company", "Firm" |
| **GST Registration (GSTIN)** | A specific GST registration number (state+category) for a legal entity. 15-character format. | "Tax Number", "VAT" |
| **Physical Site** | A factory, warehouse, or office location where operations happen. | "Branch", "Location", "Plant" (Plant implies manufacturing specifically) |
| **Store** | A logical inventory store within a site (raw material store, FG store, consumable store). | "Warehouse", "Stock location" |
| **Item** | A material, product, service, or consumable tracked in BOSS. | "Product" (product implies only finished goods), "SKU" |
| **Customer** | A buying entity that places orders with the BOSS tenant. External party. | "Client", "Account" |
| **Supplier** | A selling entity that receives purchase orders from the BOSS tenant. External party. | "Vendor" (avoid — Vendor has specific meaning in Indian supply chain) |
| **RFQ** | Request for Quotation — a customer's enquiry asking for a price. | "Enquiry", "Lead" |
| **Quotation** | The BOSS tenant's formal price offer in response to an RFQ. May have multiple revisions. | "Quote", "Offer" |
| **Quotation Revision** | An updated version of the same quotation. The original is preserved. | "New quote" |
| **Negotiation History** | The record of counter-offers and responses between customer and tenant. | "Negotiation log" |
| **CPO / Customer Purchase Order** | A confirmed order from a customer. Formal acceptance of a quotation or direct. | "Sales Order", "SO", "Order" |
| **PR / Purchase Requisition** | An internal request to procure goods. Created by production, stores, or operations. | "Purchase Request" (use Requisition) |
| **SPO / Supplier Purchase Order** | A formal order placed with a supplier. | "PO" (acceptable abbreviation), "Vendor PO" |
| **GRN / Goods Receipt Note** | Record of goods received against an SPO. Also called "SRV" (Store Receiver Voucher) in some customer vocabularies — show SRV as a gray subtitle where appropriate in UI. | "Receipt", "Receiving slip" |
| **Incoming Inspection / IIR** | Quality check on received goods before they enter stock. Also called "IR" (Incoming Report) or "IIR" (Incoming Inspection Report) in customer vocabulary — show as gray subtitle in UI. | "Inward QC", "Receiving inspection" |
| **Material Lot** | A physical batch of an item in a specific store with a quantity and cost. | "Batch", "Lot" (Lot is acceptable) |
| **Stock Movement** | Append-only ledger entry recording a quantity change. | "Stock transaction", "Stock entry" |
| **Reservation** | A hold on a material lot's quantity for a specific production job or CPO. | "Allocation", "Block" |
| **Production Job** | A manufacturing work order to produce a specific item in a specific quantity. | "Work Order", "Job Card", "MO (Manufacturing Order)" — use Job |
| **Job Stage** | A step in the production routing for a specific job. | "Operation", "Process step" |
| **Routing Template** | A reusable sequence of production stages for an item. | "Process route", "BOM routing" |
| **BOM / Bill of Materials** | The list of raw materials and components required to produce an item. | "Recipe", "Formula" |
| **Issue** | Moving material from store to a production job. | "Issue out", "Consume" |
| **Return** | Moving unused material back to store from a production job. | "Issue return" |
| **Quality Inspection** | A formal check of produced goods against specifications. | "QC check", "Inspection" (acceptable) |
| **NCR / Non-Conformance Report** | A record of a failed inspection or quality defect. | "Rejection note", "Quality failure" |
| **Concession** | Approval to use or dispatch goods that failed inspection with documented justification. | "Waiver", "Deviation" |
| **Customer Clearance Certificate** | Formal customer approval to dispatch their goods. | "Customer sign-off", "Release note" |
| **Dispatch** | Physical shipment of goods to a customer. | "Delivery", "Shipment" |
| **Challan** | Delivery challan — the document accompanying a dispatch (also called DC). | "Delivery Note" |
| **POD / Proof of Delivery** | Confirmation that goods were delivered and received by the customer. | "Delivery confirmation" |
| **Invoice** | Tax invoice issued to a customer. For B2B above ₹5 cr AATO: requires IRN under IRP. | "Bill" (use Invoice for formal documents) |
| **IRN / Invoice Reference Number** | The IRP-issued unique reference for an e-invoice under the GSTN e-invoicing mandate. | "E-invoice number" |
| **IRP / Invoice Registration Portal** | GSTN's portal where B2B tax invoices are registered to get an IRN. | "E-invoice portal" |
| **Receipt** | A payment received from a customer against one or more invoices. | "Collection", "Payment" |
| **Receipt Allocation** | Applying a receipt to a specific invoice. | "Payment posting" |
| **Supplier Bill** | An invoice received from a supplier, matched against SPO and GRN. | "Vendor invoice", "Purchase invoice" |
| **Three-Way Match** | Verification that SPO quantity, GRN quantity, and Supplier Bill quantity reconcile. | "PO matching" |
| **Opening Balance** | The initial stock, AR, and AP values loaded before a customer's go-live date. | "Migration data", "Starting balances" |
| **Asset** | An equipment, vehicle, tool, or fixture tracked for maintenance. NOT stock inventory. | "Machine" (use Asset; Machine can be an asset type) |
| **Maintenance Work Order** | A task to perform preventive or reactive maintenance on an asset. | "Service record", "Maintenance task" |
| **Estimated Cost** | The projected cost calculated from BOM, routing, and overhead rates before production. | "Budget cost", "Planned cost" |
| **Actual Cost** | The sum of actually issued materials, consumables, labour, and overhead during production. | "Real cost" |
| **Cost Variance** | Actual cost minus estimated cost. Positive = over-budget. Negative = under-budget. | "Cost difference" |
| **Frame Agreement** | A long-term commercial agreement with a customer defining terms, pricing, and volume. | "Master agreement", "Rate contract" |
| **Reorder Point** | The stock level at which a replenishment PR should be generated. | "Min stock", "Re-order level" (acceptable) |
| **Company Profile** | A curated, publishable summary of the tenant's capabilities, facilities, and credentials. | "Company overview", "About us" |

---

## Terminology: OPEN Items

| Term | Usage in meeting | Full form | Status |
|---|---|---|---|
| SRV | Used by pilot customer (Nov 15 meeting) | Unknown — possibly "Supplier Receiving Voucher" or "Stock Receiving Voucher" | OPEN: `TERM-SRV` |
| IR | Used by pilot customer (Nov 15 meeting) | Unknown — possibly "Internal Requisition" or "Inspection Report" | OPEN: `TERM-IR` |

Do not use SRV or IR in code or UI until their full forms are confirmed.

---

## GST Terms

| Term | Definition |
|---|---|
| CGST | Central GST — applies on intra-state transactions; collected by Centre |
| SGST | State GST — applies on intra-state transactions; collected by State |
| IGST | Integrated GST — applies on inter-state transactions; collected by Centre, shared with State |
| Supply type | Intra-state (CGST+SGST), Inter-state (IGST), Export (zero-rated) |
| Place of Supply | The state where supply is treated as occurring; determines CGST+SGST vs IGST |
| State Code | First two digits of GSTIN; used to determine Place of Supply |
| HSN Code | Harmonized System of Nomenclature — 4/6/8 digit classification of goods |
| SAC Code | Services Accounting Code — HSN equivalent for services |
| AATO | Annual Aggregate Turnover — determines e-invoicing eligibility (>₹5 cr mandatory) |
| GSP | GST Suvidha Provider — licensed intermediary for IRN registration via API |

---

## Role Names

| Role | Full Name | Responsibilities |
|---|---|---|
| OWNER | Business Owner | Full access; sees consolidated dashboard across all entities and sites |
| OPS_MANAGER | Operations Manager | Manages production, inventory, quality, dispatch |
| SALES_MANAGER | Sales Manager | Manages RFQs, quotations, CPOs, customers, invoices, receipts |
| PURCHASE_MANAGER | Purchase Manager | Manages PRs, SPOs, GRNs, suppliers, bills, payments |
| PRODUCTION_SUPERVISOR | Production Supervisor | Manages production jobs, stages, material issues |
| STORE_KEEPER | Store Keeper | Manages inventory, GRN stock confirmation, material issues/returns |
| ACCOUNTS_STAFF | Accounts Staff | Creates invoices, records receipts, records bills, records payments |
| QUALITY_INSPECTOR | Quality Inspector | Performs inspections, raises NCRs, records pass/fail |
| FIELD_WORKER | Field Worker | Updates job/stage status via WhatsApp only |
| PLATFORM_ADMIN | Platform Admin | Internal BOSS staff; separate auth system; zero customer-data access |
