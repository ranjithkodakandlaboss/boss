-- V046: Add category_id FK to items (references item_categories, not free-text)
ALTER TABLE items ADD COLUMN category_id UUID REFERENCES item_categories(id);
CREATE INDEX idx_items_category ON items(tenant_id, category_id);
