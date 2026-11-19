-- Checklist responses keep the item text snapshot after a template is edited.
-- The item row can be replaced; the response must not be deleted with it.
DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT con.conname INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY (con.conkey)
    WHERE nsp.nspname = 'public'
      AND rel.relname = 'inspection_checklist_responses'
      AND att.attname = 'checklist_item_id'
      AND con.contype = 'f';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format(
            'ALTER TABLE inspection_checklist_responses DROP CONSTRAINT %I',
            constraint_name);
    END IF;
END $$;

ALTER TABLE inspection_checklist_responses
    ALTER COLUMN checklist_item_id DROP NOT NULL;

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'inspection_checklist_responses_checklist_item_id_fkey'
          AND conrelid = 'inspection_checklist_responses'::regclass
    ) THEN
        ALTER TABLE inspection_checklist_responses
            ADD CONSTRAINT inspection_checklist_responses_checklist_item_id_fkey
                FOREIGN KEY (checklist_item_id) REFERENCES checklist_items(id) ON DELETE SET NULL;
    END IF;
END $$;
