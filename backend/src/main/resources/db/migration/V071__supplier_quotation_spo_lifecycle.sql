-- The locks are held until commit, and this migration restores FORCE RLS before
-- commit. This lets the schema owner reconcile every tenant atomically.
ALTER TABLE supplier_quotations DISABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_quotation_lines DISABLE ROW LEVEL SECURITY;
ALTER TABLE spos DISABLE ROW LEVEL SECURITY;
ALTER TABLE spo_lines DISABLE ROW LEVEL SECURITY;

DO $$
DECLARE
  unsupported_quotation_statuses TEXT;
  unsupported_spo_statuses TEXT;
BEGIN
  SELECT string_agg(status || '=' || row_count, ', ' ORDER BY status)
    INTO unsupported_quotation_statuses
  FROM (
    SELECT status, COUNT(*)::TEXT AS row_count
    FROM supplier_quotations
    WHERE status IN ('draft', 'expired')
    GROUP BY status
  ) unsupported;

  SELECT string_agg(status || '=' || row_count, ', ' ORDER BY status)
    INTO unsupported_spo_statuses
  FROM (
    SELECT status, COUNT(*)::TEXT AS row_count
    FROM spos
    WHERE status = 'closed'
    GROUP BY status
  ) unsupported;

  IF unsupported_quotation_statuses IS NOT NULL
     OR unsupported_spo_statuses IS NOT NULL THEN
    RAISE EXCEPTION
      'V071 cannot safely map legacy statuses (supplier_quotations: [%]; spos: [%]). Resolve these rows explicitly before retrying; draft/expired quotations and closed SPOs have no approved equivalent.',
      COALESCE(unsupported_quotation_statuses, ''),
      COALESCE(unsupported_spo_statuses, '');
  END IF;
END
$$;

-- Backfill missing SPO references only when every SPO line points to one
-- quotation whose supplier and PR match the order. Ambiguous rows are rejected
-- below rather than assigned an arbitrary quotation.
WITH candidates AS (
  SELECT
    s.id,
    s.tenant_id,
    s.supplier_id,
    s.pr_id,
    (array_agg(DISTINCT q.id) FILTER (WHERE q.id IS NOT NULL))[1] AS quotation_id,
    (array_agg(DISTINCT q.pr_id) FILTER (WHERE q.id IS NOT NULL))[1] AS quotation_pr_id,
    COUNT(sl.id) AS line_count,
    COUNT(ql.id) AS linked_line_count,
    COUNT(DISTINCT q.id) AS quotation_count,
    COALESCE(BOOL_AND(q.supplier_id = s.supplier_id)
      FILTER (WHERE q.id IS NOT NULL), FALSE) AS supplier_matches,
    COALESCE(BOOL_AND(q.pr_id IS NOT NULL)
      FILTER (WHERE q.id IS NOT NULL), FALSE) AS quotation_has_pr,
    COALESCE(BOOL_AND(s.pr_id IS NULL OR q.pr_id = s.pr_id)
      FILTER (WHERE q.id IS NOT NULL), FALSE) AS pr_matches
  FROM spos s
  LEFT JOIN spo_lines sl
    ON sl.spo_id = s.id AND sl.tenant_id = s.tenant_id
  LEFT JOIN supplier_quotation_lines ql
    ON ql.id = sl.sq_line_id AND ql.tenant_id = s.tenant_id
  LEFT JOIN supplier_quotations q
    ON q.id = ql.sq_id AND q.tenant_id = s.tenant_id
  WHERE s.sq_id IS NULL
  GROUP BY s.id, s.tenant_id, s.supplier_id, s.pr_id
)
UPDATE spos s
SET sq_id = candidates.quotation_id,
    pr_id = COALESCE(s.pr_id, candidates.quotation_pr_id)
FROM candidates
WHERE s.id = candidates.id
  AND candidates.line_count > 0
  AND candidates.linked_line_count = candidates.line_count
  AND candidates.quotation_count = 1
  AND candidates.supplier_matches
  AND candidates.quotation_has_pr
  AND candidates.pr_matches;

-- Preserve PR traceability for orders already linked directly to a quotation.
UPDATE spos s
SET pr_id = q.pr_id
FROM supplier_quotations q
WHERE s.sq_id = q.id
  AND s.tenant_id = q.tenant_id
  AND s.supplier_id = q.supplier_id
  AND s.pr_id IS NULL
  AND q.pr_id IS NOT NULL;

ALTER TABLE supplier_quotations
  ALTER COLUMN status DROP DEFAULT;
ALTER TABLE supplier_quotations
  DROP CONSTRAINT supplier_quotations_status_check;
ALTER TABLE spos
  ALTER COLUMN status DROP DEFAULT;
ALTER TABLE spos
  DROP CONSTRAINT spos_status_check;

UPDATE supplier_quotations
SET status = CASE status
  WHEN 'sent' THEN 'requested'
  WHEN 'accepted' THEN 'selected'
  ELSE status
END
WHERE status IN ('sent', 'accepted');

UPDATE spos
SET status = CASE status
  WHEN 'open' THEN 'issued'
  WHEN 'partial' THEN 'partially_received'
  WHEN 'fulfilled' THEN 'fully_received'
  ELSE status
END
WHERE status IN ('open', 'partial', 'fulfilled');

DO $$
DECLARE
  missing_quote_count BIGINT;
  missing_pr_count BIGINT;
  invalid_relationship_count BIGINT;
  unselected_quote_count BIGINT;
  mismatched_line_count BIGINT;
BEGIN
  SELECT COUNT(*) INTO missing_quote_count FROM spos WHERE sq_id IS NULL;
  SELECT COUNT(*) INTO missing_pr_count FROM spos WHERE pr_id IS NULL;
  SELECT COUNT(*) INTO invalid_relationship_count
  FROM spos s
  JOIN supplier_quotations q ON q.id = s.sq_id
  WHERE s.tenant_id <> q.tenant_id
     OR s.supplier_id <> q.supplier_id
     OR q.pr_id IS NULL
     OR s.pr_id <> q.pr_id;
  SELECT COUNT(*) INTO unselected_quote_count
  FROM spos s
  JOIN supplier_quotations q ON q.id = s.sq_id
  WHERE q.status <> 'selected';
  SELECT COUNT(*) INTO mismatched_line_count
  FROM spo_lines sl
  JOIN spos s ON s.id = sl.spo_id AND s.tenant_id = sl.tenant_id
  JOIN supplier_quotation_lines ql ON ql.id = sl.sq_line_id
  WHERE sl.sq_line_id IS NOT NULL
    AND (ql.tenant_id <> s.tenant_id OR ql.sq_id <> s.sq_id);

  IF missing_quote_count > 0 OR missing_pr_count > 0
     OR invalid_relationship_count > 0 OR unselected_quote_count > 0
     OR mismatched_line_count > 0 THEN
    RAISE EXCEPTION
      'V071 data validation failed (SPOs without quotation: %, without PR: %, mismatched tenant/supplier/PR: %, linked to non-selected quotation: %, lines from another quotation: %). Backfill only from a single matching selected quotation; resolve remaining rows explicitly before retrying.',
      missing_quote_count, missing_pr_count, invalid_relationship_count,
      unselected_quote_count, mismatched_line_count;
  END IF;
END
$$;

ALTER TABLE supplier_quotations
  ADD CONSTRAINT uq_supplier_quotations_tenant_context
  UNIQUE (tenant_id, id, supplier_id, pr_id);

ALTER TABLE spos
  ALTER COLUMN sq_id SET NOT NULL,
  ALTER COLUMN pr_id SET NOT NULL,
  ALTER COLUMN status SET DEFAULT 'draft';

ALTER TABLE spos
  DROP CONSTRAINT spos_sq_id_fkey;

ALTER TABLE spos
  ADD CONSTRAINT fk_spos_selected_quote_context
  FOREIGN KEY (tenant_id, sq_id, supplier_id, pr_id)
  REFERENCES supplier_quotations (tenant_id, id, supplier_id, pr_id);

ALTER TABLE supplier_quotations
  ADD CONSTRAINT supplier_quotations_status_check
  CHECK (status IN ('requested', 'received', 'under_review', 'selected', 'rejected'));

ALTER TABLE supplier_quotations
  ALTER COLUMN status SET DEFAULT 'requested';

ALTER TABLE spos
  ADD CONSTRAINT spos_status_check
  CHECK (status IN ('draft', 'issued', 'acknowledged', 'partially_received', 'fully_received', 'cancelled'));

ALTER TABLE supplier_quotations ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_quotations FORCE ROW LEVEL SECURITY;
ALTER TABLE supplier_quotation_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_quotation_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE spos ENABLE ROW LEVEL SECURITY;
ALTER TABLE spos FORCE ROW LEVEL SECURITY;
ALTER TABLE spo_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE spo_lines FORCE ROW LEVEL SECURITY;
