# Production and Inventory

Production does not keep its own stock quantity, lot, reservation or movement ledger. Material issue and return call `InventoryService.addMovement`.

- Issue writes an outbound `issue` movement. Return writes an inbound `return` movement.
- The movement `referenceType` is `production_job` and `referenceId` is the `job_material_issues` row.
- The lot must match the requirement item and the job site. Issue accepts `available` and `reserved` lots. Return rejects `disposed` and `quarantine` lots. A `consumed` lot can receive a return and becomes available again.
- Usable quantity is the lot quantity minus active `stock_reservation_records` for other references and any unattributed `material_lots.reserved_qty`. Production reads those rows. It does not insert reservations. The Inventory service on this baseline also does not write `reserved_qty`.
- The issue, the movement and the requirement quantity update run in one transaction. A failed Inventory call rolls the issue row back.
- The same idempotency key replays the first HTTP response and does not post a second movement.

`POST /api/v1/inventory/issues` and `POST /api/v1/inventory/returns` from the inventory contract are not on `main`. Production therefore calls the existing service method and exposes job issue and return under `/api/v1/jobs/{jobId}/materials`. Those endpoints are the production record of the issue. They are not a second stock ledger.

When Inventory changes those contracts on `main`, this branch has to be rebased and the call adapted to the merged API. This pull request must not merge before that Inventory change.
