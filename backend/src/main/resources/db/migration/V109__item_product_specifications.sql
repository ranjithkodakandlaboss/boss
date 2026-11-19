ALTER TABLE items ADD COLUMN product_kind varchar(20);
ALTER TABLE items ADD COLUMN specifications jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE items ADD CONSTRAINT items_product_kind_check
    CHECK (product_kind IS NULL OR (item_type = 'finished_goods' AND product_kind IN ('MANUFACTURED', 'TRADED')));
ALTER TABLE items ADD CONSTRAINT items_specifications_array_check
    CHECK (jsonb_typeof(specifications) = 'array');
