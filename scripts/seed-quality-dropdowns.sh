#!/usr/bin/env bash
# Add Quality and Inspection dropdown values.
#
# Fills, for each tenant:
#   INSPECTION_TYPE    Incoming, In-process, Final, Customer, Dispatch readiness
#   DEFECT_CATEGORY    that tenant's industry defect list (from rejection reasons)
#   DEFECT_SEVERITY    Critical, Major, Medium, Minor
#
# Existing options are kept. Running this twice does not duplicate rows.
#
# Local database (docker compose postgres):
#   ./scripts/seed-quality-dropdowns.sh
#
# Dev Cloud SQL, after the proxy in DEVELOPER_SETUP.md (port 5433).
# Use a role that can read tenant_registry. The app role only sees one tenant.
#   PGPASSWORD='...' PGHOST=localhost PGPORT=5433 PGUSER=postgres PGDATABASE=boss \
#     ./scripts/seed-quality-dropdowns.sh
#
# One tenant, by slug or id:
#   TENANT_SLUG=boss-local-demo ./scripts/seed-quality-dropdowns.sh
#   TENANT_ID=00000000-0000-4000-8000-000000000009 ./scripts/seed-quality-dropdowns.sh
#
# Any libpq URL:
#   DATABASE_URL='postgresql://user:pass@127.0.0.1:5433/boss' ./scripts/seed-quality-dropdowns.sh
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sql_file="$repo_root/backend/src/main/resources/db/migration/V096__quality_dropdown_options.sql"

if [[ ! -f "$sql_file" ]]; then
  echo "Seed SQL not found: $sql_file" >&2
  exit 1
fi

run_psql() {
  "$@" -v ON_ERROR_STOP=1 --single-transaction \
    -c "SELECT set_config('app.seed_tenant_id', '${TENANT_ID:-}', false);" \
    -c "SELECT set_config('app.seed_tenant_slug', '${TENANT_SLUG:-}', false);" \
    -f "$sql_file"
}

if [[ -n "${TENANT_ID:-}" && ! "${TENANT_ID}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  echo "TENANT_ID must be a UUID" >&2
  exit 1
fi

if [[ -n "${TENANT_SLUG:-}" && ! "${TENANT_SLUG}" =~ ^[a-z0-9][a-z0-9-]{0,62}$ ]]; then
  echo "TENANT_SLUG must be a lowercase slug" >&2
  exit 1
fi

if [[ -n "${DATABASE_URL:-}" || -n "${PGHOST:-}" ]]; then
  if ! command -v psql >/dev/null 2>&1; then
    echo "psql is required when DATABASE_URL or PGHOST is set" >&2
    exit 1
  fi
  if [[ -n "${DATABASE_URL:-}" ]]; then
    run_psql psql "$DATABASE_URL"
  else
    run_psql psql
  fi
  exit 0
fi

compose_file="${BOSS_SEED_COMPOSE_FILE:-$repo_root/docker/docker-compose.yml}"
compose_args=(-f "$compose_file")

if [[ ! -f "$compose_file" ]]; then
  echo "Compose file not found: $compose_file" >&2
  echo "Set DATABASE_URL or PGHOST to seed a database that is not the local compose postgres." >&2
  exit 1
fi

if [[ -n "${BOSS_SEED_COMPOSE_OVERRIDE:-}" ]]; then
  if [[ ! -f "$BOSS_SEED_COMPOSE_OVERRIDE" ]]; then
    echo "Compose override not found: $BOSS_SEED_COMPOSE_OVERRIDE" >&2
    exit 1
  fi
  compose_args+=(-f "$BOSS_SEED_COMPOSE_OVERRIDE")
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for the local database. Set DATABASE_URL to use another database." >&2
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

docker compose "${compose_args[@]}" up -d --wait postgres

docker compose "${compose_args[@]}" exec -T postgres \
  psql -U boss -d boss -v ON_ERROR_STOP=1 --single-transaction \
  -c "SELECT set_config('app.seed_tenant_id', '${TENANT_ID:-}', false);" \
  -c "SELECT set_config('app.seed_tenant_slug', '${TENANT_SLUG:-}', false);" \
  -f - < "$sql_file"
