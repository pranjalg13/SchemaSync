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

echo "==> Creating demo schema"
psql_run -q -f "$SCRIPT_DIR/demo-schema.sql"

echo "==> Seeding ${CUSTOMERS} customers, ${PRODUCTS} products, ${ORDERS} orders"
psql_run -q <<SQL
TRUNCATE main.order_items, main.orders, main.products, main.customers RESTART IDENTITY CASCADE;

INSERT INTO main.customers (email, full_name)
SELECT 'customer' || i || '@example.com', 'Customer ' || i
FROM generate_series(1, ${CUSTOMERS}) AS i;

INSERT INTO main.products (sku, name, price_cents)
SELECT 'SKU-' || lpad(i::text, 8, '0'), 'Product ' || i, (random() * 50000)::int
FROM generate_series(1, ${PRODUCTS}) AS i;

INSERT INTO main.orders (customer_id, status, amount, notes, placed_at)
SELECT (random() * (${CUSTOMERS} - 1))::int + 1,
       (ARRAY['pending','paid','shipped','delivered','cancelled'])[(random() * 4)::int + 1],
       (random() * 500000)::int,
       CASE WHEN random() < 0.7 THEN 'order note ' || i ELSE NULL END,
       now() - (random() * interval '730 days')
FROM generate_series(1, ${ORDERS}) AS i;
SQL

echo "==> Analyzing"
psql_run -q -c "ANALYZE main.customers; ANALYZE main.products; ANALYZE main.orders;"

psql_run -c "SELECT relname AS table,
                    to_char(n_live_tup, 'FM999,999,999') AS approx_rows,
                    pg_size_pretty(pg_total_relation_size(relid)) AS total_size
             FROM pg_stat_user_tables
             WHERE schemaname = 'main'
             ORDER BY pg_total_relation_size(relid) DESC;"
echo "==> Done"
