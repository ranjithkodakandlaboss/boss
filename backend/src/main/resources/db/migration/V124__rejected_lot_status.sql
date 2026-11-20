ALTER TABLE material_lots
    DROP CONSTRAINT material_lots_status_check;

ALTER TABLE material_lots
    ADD CONSTRAINT chk_material_lots_status
    CHECK (status IN ('available', 'reserved', 'quarantine', 'rejected', 'consumed', 'disposed'));
