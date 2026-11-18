# Inventory feature flags

Inventory gates use the existing `tenant_entitlements` table. Missing rows mean
the capability is enabled; a tenant row with `enabled = false` rejects the
matching write with HTTP 403. No Inventory-specific flag storage is introduced.
`GET /api/v1/inventory/features` returns the tenant's effective flag values.

| Flag | Gated behavior |
| --- | --- |
| `inventory.job.reservations` | Creating Production-job stock reservations. |
| `inventory.customer_po.reservations` | Creating Customer PO line reservations. |
| `inventory.material.issue` | Issuing reserved materials to a Production job. |
| `inventory.material.return` | Returning issued materials from a Production job. |
| `inventory.replenishment` | Saving reorder policies and automatic replenishment-request generation. |
| `inventory.transfers` | Creating inter-site stock transfers. |

Reservation release and replenishment-request cancellation remain available
when their create capability is disabled so that existing stock commitments can
be safely unwound. Already-dispatched transfers can also still be received after
transfer creation is disabled.
