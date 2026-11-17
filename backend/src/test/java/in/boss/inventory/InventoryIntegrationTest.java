package in.boss.inventory;

import in.boss.masterdata.BaseMasterDataIntegrationTest;

import java.util.UUID;

abstract class InventoryIntegrationTest extends BaseMasterDataIntegrationTest {

    protected Seed seed(String slug) {
        UUID tenantId = provisionTenant(slug);
        setTenantContext(tenantId, "STORE_KEEPER");
        UUID legalEntityId = insert("""
                INSERT INTO legal_entities (tenant_id, name, legal_name)
                VALUES (?, 'Inventory Entity', 'Inventory Entity Ltd') RETURNING id
                """, tenantId);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID siteId = insert("""
                INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code)
                VALUES (?, ?, 'Inventory Site', ?) RETURNING id
                """, tenantId, legalEntityId, "SITE-" + suffix);
        UUID storeId = insert("""
                INSERT INTO stores (tenant_id, site_id, name, code, store_type)
                VALUES (?, ?, 'Raw Store', ?, 'raw_material') RETURNING id
                """, tenantId, siteId, "RM-" + suffix);
        UUID uomId = insert("""
                INSERT INTO units_of_measure (tenant_id, name, symbol, uom_type, is_base)
                VALUES (?, 'Piece', ?, 'quantity', true) RETURNING id
                """, tenantId, "PCS-" + suffix);
        UUID itemId = insert("""
                INSERT INTO items (tenant_id, code, name, item_type, base_uom_id, reorder_qty)
                VALUES (?, ?, 'Test Resin', 'raw_material', ?, 15) RETURNING id
                """, tenantId, "ITM-" + suffix, uomId);
        return new Seed(tenantId, siteId, storeId, itemId, uomId);
    }

    protected UUID insert(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    protected record Seed(UUID tenantId, UUID siteId, UUID storeId, UUID itemId, UUID uomId) {}
}
