-- Legacy pr_type_id/pr_type_label values may represent departments, not purchase types.
-- Leave them untouched and reserve separate, tenant-bound references for new selections.
-- Single-column FKs also make the references visible to existing lifecycle discovery.
ALTER TABLE tenant_config_options
    ADD CONSTRAINT tenant_config_options_tenant_id_id_key UNIQUE (tenant_id, id);

ALTER TABLE purchase_requisitions
    ADD COLUMN priority_option_id UUID,
    ADD COLUMN priority_label_snapshot TEXT,
    ADD COLUMN purchase_type_option_id UUID,
    ADD COLUMN purchase_type_label_snapshot TEXT,
    ADD CONSTRAINT pr_priority_option_fk
        FOREIGN KEY (priority_option_id) REFERENCES tenant_config_options(id),
    ADD CONSTRAINT pr_priority_option_tenant_fk
        FOREIGN KEY (tenant_id, priority_option_id) REFERENCES tenant_config_options(tenant_id, id),
    ADD CONSTRAINT pr_purchase_type_option_fk
        FOREIGN KEY (purchase_type_option_id) REFERENCES tenant_config_options(id),
    ADD CONSTRAINT pr_purchase_type_option_tenant_fk
        FOREIGN KEY (tenant_id, purchase_type_option_id) REFERENCES tenant_config_options(tenant_id, id),
    ADD CONSTRAINT pr_priority_snapshot_pair_check
        CHECK ((priority_option_id IS NULL AND priority_label_snapshot IS NULL)
            OR (priority_option_id IS NOT NULL AND priority_label_snapshot IS NOT NULL
                AND btrim(priority_label_snapshot) <> '')),
    ADD CONSTRAINT pr_purchase_type_snapshot_pair_check
        CHECK ((purchase_type_option_id IS NULL AND purchase_type_label_snapshot IS NULL)
            OR (purchase_type_option_id IS NOT NULL AND purchase_type_label_snapshot IS NOT NULL
                AND btrim(purchase_type_label_snapshot) <> ''));

CREATE INDEX idx_pr_priority_option ON purchase_requisitions (tenant_id, priority_option_id);
CREATE INDEX idx_pr_purchase_type_option ON purchase_requisitions (tenant_id, purchase_type_option_id);
