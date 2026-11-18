-- Preserve tenant-managed option ordering changes in the append-only history.
ALTER TABLE tenant_config_option_history
  ADD COLUMN old_sort_order INTEGER,
  ADD COLUMN new_sort_order INTEGER;

ALTER TABLE tenant_config_option_history
  DROP CONSTRAINT tenant_config_option_history_change_type_check;

ALTER TABLE tenant_config_option_history
  ADD CONSTRAINT tenant_config_option_history_change_type_check
  CHECK (change_type IN ('create', 'update', 'deactivate', 'reactivate', 'REORDERED'));

ALTER TABLE tenant_config_option_history
  ADD CONSTRAINT tenant_config_option_history_reorder_values_check
  CHECK (change_type <> 'REORDERED'
    OR (old_sort_order IS NOT NULL AND new_sort_order IS NOT NULL AND old_sort_order <> new_sort_order));
