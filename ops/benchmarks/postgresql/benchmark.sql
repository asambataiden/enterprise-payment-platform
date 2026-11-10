\set ON_ERROR_STOP on
\timing on
\echo 'BENCHMARK: synthetic PostgreSQL 17 workload; no application database is accessed.'
-- This entire script runs inside an isolated, temporary Docker PostgreSQL.
-- Session-owned temporary tables cannot collide with real application tables.
SET jit = off;
CREATE TEMP TABLE bench_payments (
    id uuid PRIMARY KEY,
    merchant_id text NOT NULL,
    amount numeric(19,2) NOT NULL,
    currency text NOT NULL,
    merchant_reference text NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE TEMP TABLE bench_outbox (
    id uuid NOT NULL,
    merchant_id text NOT NULL,
    payment_id uuid NOT NULL,
    target_state text NOT NULL,
    created_at timestamptz NOT NULL,
    dispatched_at timestamptz
);
-- One hundred thousand deterministic synthetic payments:
-- 10 merchants; 10,000 rows each; nonuniform status and reference values.
INSERT INTO bench_payments
SELECT md5('payment:' || g)::uuid,
       'merchant-' || lpad(((g - 1) % 10)::text, 3, '0'),
       ((g % 99999) + 1)::numeric / 100,
       'EUR',
       'order-' || (g % 200),
       CASE WHEN g % 13 < 9 THEN 'RECEIVED'
            WHEN g % 13 < 12 THEN 'AUTHORIZED' ELSE 'CAPTURED' END,
       timestamptz '2026-01-01 00:00:00+00' + (g * interval '1 second')
FROM generate_series(1, 100000) AS g;

-- 100,000 outbox history events: 100 distinct payment IDs, 1,000 events each (100 per merchant).
-- The synthetic events are for query performance only; they are deliberately
-- not financially meaningful, and no real ledger or settlement occurs.
INSERT INTO bench_outbox
SELECT md5('event:' || g)::uuid,
       'merchant-' || lpad(((g - 1) % 10)::text, 3, '0'),
       md5('history-payment:' || (((g - 1) % 1000) / 10))::uuid,
       'AUTHORIZED',
       timestamptz '2026-01-01 00:00:00+00' + (g * interval '1 second'),
       NULL
FROM generate_series(1, 100000) AS g;
-- Choose a history payment with 100 records for one merchant. The expression
-- above assigns 10 merchants to each block of 10 events; 100 records
-- for the selected payment and tenant span the repeated cycles.
ANALYZE bench_payments;
ANALYZE bench_outbox;
\echo '========== BEFORE: payment newest-first =========='
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, amount, currency, merchant_reference, status, created_at
FROM bench_payments WHERE merchant_id = 'merchant-000'
ORDER BY created_at DESC, id DESC LIMIT 26;
\echo '========== BEFORE: payment filtered/keyset =========='
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, amount, currency, merchant_reference, status, created_at
FROM bench_payments WHERE merchant_id = 'merchant-000' AND status = 'RECEIVED'
  AND (created_at, id) < (timestamptz '2026-01-02 00:00:00+00',
                         md5('payment:40000')::uuid)
ORDER BY created_at DESC, id DESC LIMIT 26;
\echo '========== BEFORE: outbox history =========='
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, target_state, created_at, dispatched_at
FROM bench_outbox
WHERE merchant_id = 'merchant-000'
  AND payment_id = md5('history-payment:0')::uuid
ORDER BY created_at DESC, id DESC LIMIT 101;

\echo '========== CREATE V9-EQUIVALENT INDEXES =========='
-- Mirror V9's column order; benchmark table names differ by design.
CREATE INDEX bench_idx_payments_merchant_created_id
    ON bench_payments (merchant_id, created_at DESC, id DESC);
CREATE INDEX bench_idx_outbox_merchant_payment_created_id
    ON bench_outbox (merchant_id, payment_id, created_at DESC, id DESC);
ANALYZE bench_payments;
ANALYZE bench_outbox;
\echo '========== AFTER: payment newest-first =========='
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, amount, currency, merchant_reference, status, created_at
FROM bench_payments WHERE merchant_id = 'merchant-000'
ORDER BY created_at DESC, id DESC LIMIT 26;
\echo '========== AFTER: payment filtered/keyset =========='
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, amount, currency, merchant_reference, status, created_at
FROM bench_payments WHERE merchant_id = 'merchant-000' AND status = 'RECEIVED'
  AND (created_at, id) < (timestamptz '2026-01-02 00:00:00+00',
                         md5('payment:40000')::uuid)
ORDER BY created_at DESC, id DESC LIMIT 26;
\echo '========== AFTER: outbox history =========='
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, target_state, created_at, dispatched_at
FROM bench_outbox
WHERE merchant_id = 'merchant-000'
  AND payment_id = md5('history-payment:0')::uuid
ORDER BY created_at DESC, id DESC LIMIT 101;

\echo '========== INDEX SIZE AND DATASET SUMMARY =========='
SELECT 'payments' AS dataset, count(*) AS rows FROM bench_payments
UNION ALL
SELECT 'outbox', count(*) FROM bench_outbox;
SELECT relname, pg_size_pretty(pg_relation_size(oid)) AS index_size
FROM pg_class
WHERE relname IN ('bench_idx_payments_merchant_created_id',
                  'bench_idx_outbox_merchant_payment_created_id')
ORDER BY relname;
\echo 'Benchmark finished; all synthetic TEMP tables disappear with this session.'
