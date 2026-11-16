#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/docker/docker-compose.yml"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker Desktop and retry." >&2
  exit 1
fi

docker_host="${DOCKER_HOST:-$(docker context inspect "$(docker context show)" --format '{{.Endpoints.docker.Host}}')}"
case "$docker_host" in
  unix://*|npipe://*) ;;
  *)
    echo "Refusing to seed a non-local Docker endpoint: $docker_host" >&2
    exit 1
    ;;
esac

docker compose -f "$compose_file" up -d --wait postgres

if ! docker compose -f "$compose_file" exec -T postgres \
  psql -U boss -d boss -Atqc \
  "SELECT to_regprocedure('public.seed_tenant_defaults(uuid,text)') IS NOT NULL
     AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boss_app_role')" | grep -qx t; then
  echo "BOSS migrations are not applied. Start the backend with the local profile once, then rerun this script." >&2
  exit 1
fi

docker compose -f "$compose_file" exec -T postgres \
  psql -U boss -d boss -v ON_ERROR_STOP=1 <<'SQL'
\set ON_ERROR_STOP on
\set tenant_id '00000000-0000-4000-8000-000000000009'

BEGIN;

INSERT INTO customer_groups (name, slug)
VALUES ('BOSS Local Demo Group', 'boss-local-demo-group')
ON CONFLICT (slug) DO UPDATE
  SET name = EXCLUDED.name, status = 'active'
RETURNING id AS group_id
\gset

SET LOCAL ROLE boss_app_role;
SELECT set_config('app.current_tenant_id', :'tenant_id', true);

INSERT INTO tenant_registry (id, customer_group_id, name, slug, status, plan_tier)
VALUES (:'tenant_id'::uuid, :'group_id'::uuid, 'BOSS Local Demo Tenant',
        'boss-local-demo', 'active', 'pilot')
ON CONFLICT (slug) DO UPDATE
  SET customer_group_id = EXCLUDED.customer_group_id,
      name = EXCLUDED.name,
      status = 'active',
      plan_tier = EXCLUDED.plan_tier;

SELECT seed_tenant_defaults(:'tenant_id'::uuid, 'GENERAL_MANUFACTURING');

INSERT INTO legal_entities (id, tenant_id, name, legal_name, entity_type)
VALUES ('00000000-0000-4000-8000-000000000011', :'tenant_id'::uuid,
        'BOSS Local Demo Works', 'BOSS Local Demo Works', 'OTHER')
ON CONFLICT (id) DO UPDATE
  SET name = EXCLUDED.name,
      legal_name = EXCLUDED.legal_name,
      entity_type = EXCLUDED.entity_type
  WHERE legal_entities.tenant_id = EXCLUDED.tenant_id;

INSERT INTO physical_sites (tenant_id, legal_entity_id, name, code, site_type)
VALUES (:'tenant_id'::uuid, '00000000-0000-4000-8000-000000000011',
        'Local Demo Factory', 'LOCAL-DEMO', 'factory')
ON CONFLICT (tenant_id, code) DO UPDATE
  SET legal_entity_id = EXCLUDED.legal_entity_id,
      name = EXCLUDED.name,
      site_type = EXCLUDED.site_type
RETURNING id AS site_id
\gset

INSERT INTO stores (tenant_id, site_id, name, code, store_type)
VALUES (:'tenant_id'::uuid, :'site_id'::uuid, 'Demo Raw Materials',
        'DEMO-RAW', 'raw_material')
ON CONFLICT (tenant_id, site_id, code) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO app_users (tenant_id, firebase_uid, email, display_name, status)
VALUES
  (:'tenant_id'::uuid, 'local-seed-owner', 'owner@boss.local.test', 'Local Demo Owner', 'active'),
  (:'tenant_id'::uuid, 'local-seed-ops-manager', 'ops-manager@boss.local.test', 'Local Demo Operations Manager', 'active'),
  (:'tenant_id'::uuid, 'local-seed-sales-manager', 'sales-manager@boss.local.test', 'Local Demo Sales Manager', 'active'),
  (:'tenant_id'::uuid, 'local-seed-purchase-manager', 'purchase-manager@boss.local.test', 'Local Demo Purchase Manager', 'active'),
  (:'tenant_id'::uuid, 'local-seed-production-supervisor', 'production-supervisor@boss.local.test', 'Local Demo Production Supervisor', 'active'),
  (:'tenant_id'::uuid, 'local-seed-store-keeper', 'store-keeper@boss.local.test', 'Local Demo Store Keeper', 'active'),
  (:'tenant_id'::uuid, 'local-seed-accounts-staff', 'accounts-staff@boss.local.test', 'Local Demo Accounts Staff', 'active'),
  (:'tenant_id'::uuid, 'local-seed-quality-inspector', 'quality-inspector@boss.local.test', 'Local Demo Quality Inspector', 'active'),
  (:'tenant_id'::uuid, 'local-seed-field-worker', 'field-worker@boss.local.test', 'Local Demo Field Worker', 'active')
ON CONFLICT (tenant_id, email) DO UPDATE
  SET firebase_uid = EXCLUDED.firebase_uid,
      display_name = EXCLUDED.display_name,
      status = 'active';

INSERT INTO user_roles (tenant_id, user_id, role, site_id)
SELECT u.tenant_id, u.id, role_seed.role, :'site_id'::uuid
FROM (VALUES
  ('owner@boss.local.test', 'OWNER'),
  ('ops-manager@boss.local.test', 'OPS_MANAGER'),
  ('sales-manager@boss.local.test', 'SALES_MANAGER'),
  ('purchase-manager@boss.local.test', 'PURCHASE_MANAGER'),
  ('production-supervisor@boss.local.test', 'PRODUCTION_SUPERVISOR'),
  ('store-keeper@boss.local.test', 'STORE_KEEPER'),
  ('accounts-staff@boss.local.test', 'ACCOUNTS_STAFF'),
  ('quality-inspector@boss.local.test', 'QUALITY_INSPECTOR'),
  ('field-worker@boss.local.test', 'FIELD_WORKER')
) AS role_seed(email, role)
JOIN app_users u ON u.tenant_id = :'tenant_id'::uuid
                AND u.email = role_seed.email
ON CONFLICT (tenant_id, user_id, role, site_id) DO NOTHING;

SELECT id AS nos_uom_id
FROM units_of_measure
WHERE tenant_id = :'tenant_id'::uuid AND symbol = 'NOS'
\gset

SELECT id AS kg_uom_id
FROM units_of_measure
WHERE tenant_id = :'tenant_id'::uuid AND symbol = 'KG'
\gset

INSERT INTO customers (tenant_id, code, name, legal_name, customer_type)
VALUES (:'tenant_id'::uuid, 'DEMO-CUSTOMER-001',
        'Synthetic Demo Customer', 'Synthetic Demo Customer Ltd', 'business')
ON CONFLICT (tenant_id, code) DO UPDATE
  SET name = EXCLUDED.name,
      legal_name = EXCLUDED.legal_name,
      customer_type = EXCLUDED.customer_type,
      is_active = TRUE;

INSERT INTO suppliers (tenant_id, code, name, legal_name, supplier_type)
VALUES (:'tenant_id'::uuid, 'DEMO-SUPPLIER-001',
        'Synthetic Demo Supplier', 'Synthetic Demo Supplier Ltd', 'manufacturer')
ON CONFLICT (tenant_id, code) DO UPDATE
  SET name = EXCLUDED.name,
      legal_name = EXCLUDED.legal_name,
      supplier_type = EXCLUDED.supplier_type,
      is_active = TRUE;

INSERT INTO items (tenant_id, code, name, description, item_type, base_uom_id, std_cost)
VALUES (:'tenant_id'::uuid, 'DEMO-RAW-001', 'Synthetic Steel Plate',
        'Synthetic local-development raw material', 'raw_material', :'kg_uom_id'::uuid, 125.00)
ON CONFLICT (tenant_id, code) DO UPDATE
  SET name = EXCLUDED.name,
      description = EXCLUDED.description,
      item_type = EXCLUDED.item_type,
      base_uom_id = EXCLUDED.base_uom_id,
      std_cost = EXCLUDED.std_cost,
      is_active = TRUE
RETURNING id AS raw_item_id
\gset

INSERT INTO items (tenant_id, code, name, description, item_type, base_uom_id)
VALUES (:'tenant_id'::uuid, 'DEMO-FG-001', 'Synthetic Fabricated Bracket',
        'Synthetic local-development finished good', 'finished_goods', :'nos_uom_id'::uuid)
ON CONFLICT (tenant_id, code) DO UPDATE
  SET name = EXCLUDED.name,
      description = EXCLUDED.description,
      item_type = EXCLUDED.item_type,
      base_uom_id = EXCLUDED.base_uom_id,
      is_active = TRUE
RETURNING id AS finished_item_id
\gset

INSERT INTO bom_headers
  (tenant_id, finished_item_id, version, status, is_default, created_by)
VALUES (:'tenant_id'::uuid, :'finished_item_id'::uuid, 'local-demo-v1',
        'approved', TRUE, NULL)
ON CONFLICT (tenant_id, finished_item_id, version) DO UPDATE
  SET status = 'approved', is_default = TRUE
RETURNING id AS bom_id
\gset

INSERT INTO bom_lines
  (id, tenant_id, bom_header_id, component_item_id, quantity, uom_id, level)
VALUES ('00000000-0000-4000-8000-000000000012', :'tenant_id'::uuid,
        :'bom_id'::uuid, :'raw_item_id'::uuid, 2.5, :'kg_uom_id'::uuid, 1)
ON CONFLICT (id) DO UPDATE
  SET bom_header_id = EXCLUDED.bom_header_id,
      component_item_id = EXCLUDED.component_item_id,
      quantity = EXCLUDED.quantity,
      uom_id = EXCLUDED.uom_id
  WHERE bom_lines.tenant_id = EXCLUDED.tenant_id;

COMMIT;

SELECT format(
  'Seeded tenant %s: %s users, %s roles, %s items, %s customers, %s suppliers',
  'boss-local-demo',
  (SELECT count(*) FROM app_users
     WHERE tenant_id = :'tenant_id'::uuid AND firebase_uid LIKE 'local-seed-%'),
  (SELECT count(*) FROM user_roles r
     JOIN app_users u ON u.tenant_id = r.tenant_id AND u.id = r.user_id
     WHERE r.tenant_id = :'tenant_id'::uuid AND u.firebase_uid LIKE 'local-seed-%'),
  (SELECT count(*) FROM items WHERE tenant_id = :'tenant_id'::uuid
     AND code IN ('DEMO-RAW-001', 'DEMO-FG-001')),
  (SELECT count(*) FROM customers WHERE tenant_id = :'tenant_id'::uuid
     AND code = 'DEMO-CUSTOMER-001'),
  (SELECT count(*) FROM suppliers WHERE tenant_id = :'tenant_id'::uuid
     AND code = 'DEMO-SUPPLIER-001')
);
SQL
