# BOSS — Cross-Domain Integration Standards
**Version:** v1.1 | **Date:** 15 November 2026

---

## 1. API Response Envelope

All BOSS REST APIs return this JSON envelope:

```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "meta": {
    "requestId": "uuid",
    "timestamp": "2026-11-15T10:00:00Z"
  }
}
```

Error response:
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "INVENTORY_INSUFFICIENT",
    "message": "Insufficient available quantity for item SKU-001",
    "details": { "available": 5.0, "requested": 10.0 }
  },
  "meta": { ... }
}
```

Error codes use SCREAMING_SNAKE_CASE. All error codes are registered in `11_COMMON_AGENT_CONTEXT/error-codes.md`.

---

## 2. HTTP Status Conventions

| Status | When to use |
|---|---|
| 200 OK | Successful GET, successful PUT/PATCH with response body |
| 201 Created | Successful POST that creates a resource |
| 204 No Content | Successful DELETE or POST with no response body |
| 400 Bad Request | Validation failure, malformed request |
| 401 Unauthorized | Missing or invalid JWT |
| 403 Forbidden | Valid JWT but insufficient role/permission |
| 404 Not Found | Resource not found (tenant-scoped — not found includes not-in-tenant) |
| 409 Conflict | State conflict (e.g. trying to cancel a FULFILLED CPO) |
| 422 Unprocessable | Business rule violation (e.g. insufficient stock) |
| 429 Too Many Requests | Rate limiting |
| 500 Internal Server Error | Unexpected error |

Never return 404 when RLS prevents access to another tenant's resource. The resource appears not to exist. Use 404 consistently.

---

## 3. Pagination

All list endpoints must support cursor-based pagination:

Request: `GET /api/v1/inventory/items?cursor={cursor}&limit={limit}&sort=code&dir=asc`

Response:
```json
{
  "success": true,
  "data": {
    "items": [...],
    "nextCursor": "base64encodedcursor",
    "hasMore": true,
    "total": null
  }
}
```

Default limit: 50. Maximum limit: 200. Never return unbounded lists.

---

## 4. Idempotency Key Header

All POST endpoints that create resources or trigger external calls must accept:

```
Idempotency-Key: {client-generated-uuid}
```

If the same Idempotency-Key is received within 24 hours, return the original response without re-executing.

The backend stores idempotency results in `idempotency_keys`.

---

## 5. Cross-Domain Event Contracts

Events are published via the outbox relay to Pub/Sub.

Event naming convention: `{domain}.{entity}.{past_tense_verb}`

Examples:
- `inventory.stock_movement.created`
- `sales.cpo.feasibility_approved`
- `production.job.completed`
- `dispatch.dispatch.delivered`
- `finance.invoice.irn_registered`

Event payload structure:
```json
{
  "eventType": "production.job.completed",
  "tenantId": "uuid",
  "entityId": "uuid",
  "timestamp": "ISO-8601",
  "version": "1",
  "payload": { ... domain-specific fields ... }
}
```

Event consumers must check `idempotency_keys` before processing to handle duplicate delivery.

All event contracts are registered in `10_API_CONTRACTS/event-contract-register.md`.

---

## 6. Cross-Domain State Triggers

| Trigger | Producing domain | Consuming domain | Mechanism |
|---|---|---|---|
| GRN `inspection_passed` | Receiving | Inventory | Outbox event → `StockReceiptService.receiveFromGrn()` |
| CPO `feasibility_approved` | Feasibility | Production | Outbox event → Production creates job |
| Job `completed` | Production | Quality | Outbox event → `QualityService.createInspection()` |
| Inspection `pass` | Quality | Dispatch | Dispatch eligibility unlocked |
| Dispatch `delivered` | Dispatch | Finance | Outbox event → `InvoiceService.createFromDispatch()` |
| Supplier Bill `matched` | Finance | Procurement | Three-way match status updated on SPO |
| Receipt allocated | Finance | Finance | `invoice.amount_paid` and `amount_due` updated |

---

## 7. BossContext — The Request Context Object

```java
public record BossContext(
    UUID tenantId,
    UUID userId,
    Set<String> roles,
    UUID siteId,  // optional — site-scoped operations
    String firebaseUid
) {
    public boolean hasRole(String role) { return roles.contains(role); }
    public boolean hasAnyRole(String... r) { return Arrays.stream(r).anyMatch(roles::contains); }
}
```

BossContext is populated by `JwtAuthenticationFilter` and stored in `BossContextHolder` (ThreadLocal).

The HikariCP override reads `BossContextHolder.getContext()` to set `app.current_tenant_id`.

BossContextHolder MUST be cleared in a finally block at the end of each request.

---

## 8. Monetary Amounts

- All monetary values stored as `NUMERIC(15,2)` in PostgreSQL
- Never use `float` or `double` for money in Java — use `BigDecimal`
- JSON serialises monetary amounts as strings to prevent floating-point precision loss: `"amount": "12345.67"`
- GST amounts calculated by: `taxableAmount × rate / 100`, rounded HALF_UP to 2 decimal places
- For intra-state: `cgst = igst = 0; cgst_rate = sgst_rate = gst_rate / 2`
- For inter-state: `cgst = sgst = 0; igst_rate = gst_rate`

---

## 9. Date and Time

- All timestamps stored as `TIMESTAMPTZ` in PostgreSQL (UTC)
- All API timestamps in ISO-8601 format with timezone: `"2026-11-15T10:30:00+05:30"`
- All date-only fields (invoice_date, order_date) stored as `DATE` in PostgreSQL
- Server time zone: UTC; Indian Standard Time (IST = UTC+5:30) conversion in frontend only

---

## 10. Soft Delete vs Hard Delete

| Table type | Delete strategy |
|---|---|
| Master data (items, customers, suppliers) | `is_active = FALSE` — never hard delete |
| Transaction headers (invoices, CPOs, jobs) | `status = 'cancelled'` — never hard delete |
| Append-only ledgers (stock_movements, audit_events) | No delete — ever |
| Reference data (hsn_codes, uom) | No delete during pilot |
| Config data (reorder_policies, routing templates) | `is_active = FALSE` |

---

## 11. File Attachments

All file attachments (POD signatures, quality photos, dispatch challan PDFs, company profile PDF) are stored in Google Cloud Storage.

- Bucket naming: `boss-{env}-attachments`
- Object path: `{tenantId}/{domain}/{entityId}/{filename}`
- Never store binary data in PostgreSQL
- API returns a time-limited signed URL for download (900 seconds)
- Upload via frontend: get a signed upload URL from API, then PUT directly to GCS
