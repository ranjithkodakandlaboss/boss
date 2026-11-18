# Production and Inventory

Production does not keep its own stock quantity, lot, reservation or movement ledger.

- A material issue reserves the requested quantity for the job when that lot does not already have an active reservation, then calls `InventoryService.issueReservedMaterial()`. That writes one outbound `issue` movement with `referenceType` `JOB` and `referenceId` of the job. It does not insert `job_material_issues`. Production inserts that row once, with the movement id, requirement, and source traceability.
- A material return keeps Production's quantity check against the source issue and calls `InventoryService.addMovement()` once. The return movement uses `referenceType` `production_job` and `referenceId` of the production return row. Production does not call `InventoryService.returnMaterial()`, which would insert a second return row.
- Cancelling a job calls `InventoryService.releaseJobReservations(jobId)` so any active reservation that was not issued is released.
- The lot must match the requirement item and the job site. Issue accepts `available` and `reserved` lots. Return rejects `disposed` and `quarantine` lots. A `consumed` lot can receive a return and becomes available again.
- Usable quantity is the lot quantity minus active reservations for other references and any unattributed `material_lots.reserved_qty`.
- The reservation, movement, production issue row, and requirement quantity update run in one transaction. A failed Inventory call rolls the issue row back.
- The same idempotency key replays the first HTTP response and does not post a second movement.

Production exposes issue and return under `/api/v1/jobs/{jobId}/materials`. Those rows are the production record. They are not a second stock ledger.
