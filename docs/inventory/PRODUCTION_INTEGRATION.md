# Production integration

Production material issue and cancellation flows must use the Inventory reservation service so lot
balances and reservation records stay consistent.

- Reserve a job's material requirements with `InventoryService.reserveForJobRequirements(jobId, false)`.
  This snapshots the approved BOM if the job has no material-requirement rows and fails without
  partial reservations when stock is insufficient.
- Issue reserved material with `InventoryService.issueReservedMaterial(...)`, not
  `addMovement(..., "issue", ...)`. The method atomically reduces lot quantity and reserved quantity,
  consumes the job's reservation, and records the stock movement. It deliberately does not create
  `job_material_issues`; Production should write its richer traceability row using the returned
  movement ID in the same transaction.
- Keep Production's return validation and traceability row, and post the stock increase through
  `InventoryService.addMovement(..., "return", ...)`. Do not also call
  `InventoryService.returnMaterial(...)`, which writes an Inventory-owned job issue row.
- After a job is cancelled, call `InventoryService.releaseJobReservations(jobId)` in the same
  transaction as the cancellation.

Inventory currently uses migration V087 for Customer PO reservation links. Production PR #164 also
uses V087; after Inventory lands first, renumber the Production migration to V088 when rebasing it.
