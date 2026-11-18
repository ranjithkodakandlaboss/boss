# Production metrics

Meters are Micrometer counters, one timer and one distribution summary. Success counters are recorded after the database transaction commits, so a rollback or a retried request that never commits is not counted as success. HTTP retries use `Idempotency-Key` and replay the first response. Labels are bounded outcomes. Tenant and customer identifiers are not labels.

| Meter | Labels | When it is recorded |
| --- | --- | --- |
| `boss.production.job.creation` | `result` = success or failure | Job insert commits, or the create attempt fails. |
| `boss.production.job.transition` | `from`, `to` | A job status change commits. |
| `boss.production.stage.completion` | `result` = completed, skipped or failure | A stage close commits, or the attempt fails. |
| `boss.production.stage.duration` | none | Elapsed time from stage start to stage completion. |
| `boss.production.material.movement` | `type` = issue or return, `result` | An issue or return commits, or the attempt fails. |
| `boss.production.inventory.integration` | `operation` = issue or return, `result` | The Inventory movement commits, or Inventory rejects the call. |
| `boss.production.costing.calculation` | `result` | A costing refresh commits. |
| `boss.production.cost.variance` | none | Actual total minus estimated total, recorded with the costing refresh. |
| `boss.production.inspection.gate` | `result` = passed, failed or blocked | A stage is allowed through, a failed inspection is stored, or completion is refused. |

`GET /api/v1/jobs/metrics` reads persisted jobs and material issues for the current tenant:

- active jobs, completed jobs, delayed jobs
- counts by job status and by current stage name
- material issued, returned and consumed quantities
- estimated cost, actual cost and cost variance for commercial roles; other roles receive null money fields

Stage duration and throughput use the same persisted stage timestamps. No separate forecast formula is applied. Risk on a job is the stored classification: completed or cancelled is complete, on hold is blocked, a material shortfall is awaiting material, a planned end in the past is delayed, a planned end within three days is at risk, otherwise the job is on track.
