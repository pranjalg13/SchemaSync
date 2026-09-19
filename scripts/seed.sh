#!/usr/bin/env bash
# Seed the demo dataset.
#
#   ./scripts/seed.sh              # 3,500,000 orders  (~500MB, the dev default)
#   ./scripts/seed.sh 35000000     # 35,000,000 orders (~5GB, the Phase 7 validation run)
#
# Generates rows server-side via generate_series, so seeding 5GB does not stream
# gigabytes through a client connection.
set -euo pipefail

ORDERS="${1:-3500000}"
CUSTOMERS="${SEED_CUSTOMERS:-50000}"
PRODUCTS="${SEED_PRODUCTS:-5000}"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-schemasync}"
PGDATABASE="${PGDATABASE:-schemasync}"
export PGPASSWORD="${PGPASSWORD:-schemasync}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
psql_run() { psql -v ON_ERROR_STOP=1 -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" "$@"; }

DEMO_DIR="$SCRIPT_DIR/../backend/src/main/resources/demo"

echo "==> Creating demo schema (drops and recreates schema \"main\")"
psql_run -q -f "$DEMO_DIR/schema.sql"

echo "==> Seeding ${CUSTOMERS} customers, ${PRODUCTS} products, ${ORDERS} orders"
sed -e "s/{{customers}}/${CUSTOMERS}/g" -e "s/{{products}}/${PRODUCTS}/g" -e "s/{{orders}}/${ORDERS}/g" \
    "$DEMO_DIR/seed.sql" | psql_run -q

psql_run -c "SELECT relname AS table,
                    to_char(n_live_tup, 'FM999,999,999') AS approx_rows,
                    pg_size_pretty(pg_total_relation_size(relid)) AS total_size
             FROM pg_stat_user_tables
             WHERE schemaname = 'main'
             ORDER BY pg_total_relation_size(relid) DESC;"
echo "==> Done"
