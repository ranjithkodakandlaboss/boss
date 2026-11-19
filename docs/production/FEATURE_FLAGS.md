# Production feature flags

Production uses the existing `tenant_entitlements` table. A tenant with no row for a flag has that capability enabled. A row with `enabled = false` rejects the matching API with HTTP 403 and hides the control in the customer app. There is no separate flag framework.

| Flag | What it gates |
| --- | --- |
| `production.job.create` | Creating a job from an eligible Customer PO line. |
| `production.routing.stages` | Applying a routing template and copying its stages. Job creation requires this flag as well as job creation. |
| `production.stage.transition` | Start, complete, skip, hold and release-hold. |
| `production.material.issue` | Issuing a lot to a job. |
| `production.material.return` | Returning unused material from an issue. |
| `production.job.costing` | Reading and posting labour, overhead and other cost entries. Material issue and return still refresh persisted actual cost when this flag is off, so the ledger is not left stale. |
| `production.inspection.gate` | Opening an inspection, recording a result, approving a concession, and blocking stage completion until a pass or an approved concession. |

`GET /api/v1/jobs/features` returns the current tenant snapshot. Disabled flags cannot be bypassed by calling the API directly.

## Manufacturing configuration

Tenant owners and operations managers can create shared routes or associate a route with an active finished product. Routes use the existing production stage reference data and revisions; an existing job retains its copied stages and original routing version. Job creation still requires the user to select an eligible route; product-specific routes are filtered alongside shared routes, not automatically selected.

Route deletion, deactivation, reassignment to another product, and arbitrary stage dependency graphs are not supported. The admin editor preserves existing stage settings that it does not edit; a revision can be rejected if a referenced stage, inspection template, or asset capability is no longer valid. Legacy routes containing an inspection type that cannot be represented in versioned stages cannot be revised through this API. Stage order alone is not a general dependency model. Asset-stage mapping and maintenance impacts are separate capabilities.
