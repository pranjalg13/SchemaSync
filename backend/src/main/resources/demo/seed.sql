-- Demo rows. {{customers}}, {{products}} and {{orders}} are substituted by the caller
-- (DemoBootstrap at startup, or scripts/seed.sh). Generated server-side with generate_series, so
-- seeding millions of rows never streams them through a client connection.

INSERT INTO main.customers (email, full_name)
SELECT 'customer' || i || '@example.com', 'Customer ' || i
FROM generate_series(1, {{customers}}) AS i;

INSERT INTO main.products (sku, name, price_cents)
SELECT 'SKU-' || lpad(i::text, 8, '0'), 'Product ' || i, (random() * 50000)::int
FROM generate_series(1, {{products}}) AS i;

INSERT INTO main.orders (customer_id, status, amount, notes, placed_at)
SELECT (random() * ({{customers}} - 1))::int + 1,
       (ARRAY['pending','paid','shipped','delivered','cancelled'])[(random() * 4)::int + 1],
       (random() * 500000)::int,
       CASE WHEN random() < 0.7 THEN 'order note ' || i ELSE NULL END,
       now() - (random() * interval '730 days')
FROM generate_series(1, {{orders}}) AS i;

-- The planner decides ATOMIC vs ONLINE from pg_class.reltuples, which stays at -1 until the table
-- is analyzed. Without this a freshly seeded demo would plan every migration as if it were empty.
ANALYZE main.customers;
ANALYZE main.products;
ANALYZE main.orders;
