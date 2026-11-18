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
