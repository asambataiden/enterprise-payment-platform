\set ON_ERROR_STOP on
\timing on
\echo 'STATISTICAL LAB: synthetic, isolated PostgreSQL database only'
SET jit = off;
SET max_parallel_workers_per_gather = 0;

-- The runner destroys the isolated container; none of these rows are real.
-- Real tables (rather than TEMP) preserve WAL effects for write comparisons.
CREATE TABLE lab_payments_seed AS
SELECT md5('payment:' || g)::uuid AS id,
       'merchant-' || lpad(((g - 1) % 10)::text, 3, '0') AS merchant_id,
       ((g % 99999) + 1)::numeric / 100 AS amount,
       'EUR'::text AS currency,
       'order-' || (g % 200) AS merchant_reference,
       CASE WHEN g % 13 < 9 THEN 'RECEIVED'
            WHEN g % 13 < 12 THEN 'AUTHORIZED' ELSE 'CAPTURED' END AS status,
       timestamptz '2026-01-01 00:00:00+00' + (g * interval '1 second') AS created_at
FROM generate_series(1, 100000) g;

CREATE TABLE lab_history_seed AS
SELECT md5('event:' || g)::uuid AS id,
       'merchant-' || lpad(((g - 1) % 10)::text, 3, '0') AS merchant_id,
       md5('history-payment:' || (((g - 1) % 1000) / 10))::uuid AS payment_id,
       'AUTHORIZED'::text AS target_state,
       timestamptz '2026-01-01 00:00:00+00' + (g * interval '1 second') AS created_at,
       NULL::timestamptz AS dispatched_at
FROM generate_series(1, 100000) g;

CREATE TABLE lab_payments_plain AS TABLE lab_payments_seed;
CREATE TABLE lab_payments_indexed AS TABLE lab_payments_seed;
CREATE TABLE lab_history_plain AS TABLE lab_history_seed;
CREATE TABLE lab_history_indexed AS TABLE lab_history_seed;

CREATE UNIQUE INDEX lab_payments_plain_pk ON lab_payments_plain (id);
CREATE UNIQUE INDEX lab_payments_indexed_pk ON lab_payments_indexed (id);
CREATE INDEX lab_payments_keyset_idx
    ON lab_payments_indexed (merchant_id, created_at DESC, id DESC);
CREATE INDEX lab_history_merchant_payment_idx
    ON lab_history_indexed (merchant_id, payment_id, created_at DESC, id DESC);
ANALYZE lab_payments_plain;
ANALYZE lab_payments_indexed;
ANALYZE lab_history_plain;
ANALYZE lab_history_indexed;

CREATE TABLE lab_samples (
    workload text NOT NULL,
    variant text NOT NULL CHECK (variant IN ('plain', 'indexed')),
    sample_no integer NOT NULL,
    duration_ms double precision NOT NULL,
    warmup boolean NOT NULL,
    rows_observed bigint,
    PRIMARY KEY (workload, variant, sample_no)
);

-- Repeated serial read timings: 8 warm-up pairs and 40 measured pairs.
-- Reverse comparison order on alternate rounds to reduce order-related bias.
-- We include aggregates to force the DB to retrieve non-indexed attributes.
DO $$
DECLARE
    round_no int;
    workload text;
    variant text;
    ordered_variants text[];
    table_name text;
    query_text text;
    observed numeric;
    started timestamptz;
    elapsed double precision;
BEGIN
    FOREACH workload IN ARRAY ARRAY['newest','keyset','history'] LOOP
        FOR round_no IN 1..48 LOOP
            ordered_variants := CASE WHEN round_no % 2 = 0
                  THEN ARRAY['indexed','plain'] ELSE ARRAY['plain','indexed'] END;
            FOREACH variant IN ARRAY ordered_variants LOOP
                table_name := CASE WHEN workload = 'history'
                     THEN 'lab_history_' || variant ELSE 'lab_payments_' || variant END;
                IF workload = 'newest' THEN
                    query_text := format(
                      'SELECT sum(amount) FROM (SELECT amount FROM %I
                       WHERE merchant_id = %L
                       ORDER BY created_at DESC, id DESC LIMIT 26) q',
                       table_name, 'merchant-000');
                ELSIF workload = 'keyset' THEN
                    query_text := format(
                      'SELECT sum(amount) FROM (SELECT amount FROM %I
                       WHERE merchant_id = %L AND status = %L
                       AND (created_at, id) < (%L::timestamptz, %L::uuid)
                       ORDER BY created_at DESC, id DESC LIMIT 26) q',
                       table_name, 'merchant-000', 'RECEIVED',
                       '2026-01-02 00:00:00+00',
                       md5('payment:40000')::uuid);
                ELSE
                    query_text := format(
                      'SELECT count(target_state) FROM (SELECT target_state FROM %I
                       WHERE merchant_id = %L AND payment_id = %L::uuid
                       ORDER BY created_at DESC, id DESC LIMIT 101) q',
                       table_name, 'merchant-000',
                       md5('history-payment:0')::uuid);
                END IF;
                started := clock_timestamp();
                EXECUTE query_text INTO observed;
                elapsed := extract(epoch FROM (clock_timestamp() - started)) * 1000.0;
                INSERT INTO lab_samples
                    (workload, variant, sample_no, duration_ms, warmup, rows_observed)
                VALUES (workload, variant, round_no, elapsed, round_no <= 8,
                        CASE WHEN workload = 'history' THEN observed::bigint ELSE NULL END);
            END LOOP;
        END LOOP;
    END LOOP;
END $$;

\echo '========== READ TIMINGS: 40 measured samples per variant =========='
SELECT workload, variant, count(*) AS samples,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms)::numeric, 3) AS median_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)::numeric, 3) AS p95_ms,
       round(min(duration_ms)::numeric, 3) AS min_ms,
       round(max(duration_ms)::numeric, 3) AS max_ms
FROM lab_samples WHERE NOT warmup
GROUP BY workload, variant ORDER BY workload, variant;

\echo '========== READ PLANS: review both variants =========='
EXPLAIN (ANALYZE, BUFFERS)
SELECT sum(amount) FROM (
 SELECT amount FROM lab_payments_plain
 WHERE merchant_id = 'merchant-000'
 ORDER BY created_at DESC, id DESC LIMIT 26
) q;
EXPLAIN (ANALYZE, BUFFERS)
SELECT sum(amount) FROM (
 SELECT amount FROM lab_payments_indexed
 WHERE merchant_id = 'merchant-000'
 ORDER BY created_at DESC, id DESC LIMIT 26
) q;
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(target_state) FROM (
 SELECT target_state FROM lab_history_plain
 WHERE merchant_id = 'merchant-000'
 AND payment_id = md5('history-payment:0')::uuid
 ORDER BY created_at DESC, id DESC LIMIT 101
) q;
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(target_state) FROM (
 SELECT target_state FROM lab_history_indexed
 WHERE merchant_id = 'merchant-000'
 AND payment_id = md5('history-payment:0')::uuid
 ORDER BY created_at DESC, id DESC LIMIT 101
) q;

-- Insert-overhead comparison. Both tables have the same primary key;
-- the indexed variant has one additional V9-equivalent secondary index.
CREATE TABLE lab_write_plain (
    id uuid PRIMARY KEY,
    merchant_id text NOT NULL,
    created_at timestamptz NOT NULL,
    amount numeric(19,2) NOT NULL
);
CREATE TABLE lab_write_indexed (LIKE lab_write_plain INCLUDING ALL);
CREATE INDEX lab_write_keyset_idx
    ON lab_write_indexed (merchant_id, created_at DESC, id DESC);
CREATE TABLE lab_write_samples (
    variant text NOT NULL,
    batch_no int NOT NULL,
    elapsed_ms double precision NOT NULL,
    rows_inserted int NOT NULL,
    PRIMARY KEY (variant, batch_no)
);

-- 12 blocks of 1,000 inserts per variant, alternating order by batch.
-- Measurements include INSERT execution and index maintenance, and use
-- real tables so PostgreSQL writes WAL; commit/fsync is not isolated.
DO $$
DECLARE
    batch_no int;
    variant text;
    ordered_variants text[];
    stmt text;
    started timestamptz;
    elapsed double precision;
    inserted bigint;
BEGIN
    FOR batch_no IN 1..12 LOOP
        ordered_variants := CASE WHEN batch_no % 2 = 0
              THEN ARRAY['indexed','plain'] ELSE ARRAY['plain','indexed'] END;
        FOREACH variant IN ARRAY ordered_variants LOOP
            stmt := format($sql$
                INSERT INTO %I (id, merchant_id, created_at, amount)
                SELECT md5(%L || g)::uuid,
                       'merchant-' || lpad(((g - 1) %% 10)::text, 3, '0'),
                       timestamptz '2026-01-01 00:00:00+00' + (g * interval '1 second'),
                       ((g %% 99999) + 1)::numeric / 100
                FROM generate_series(%s, %s) g
                $sql$, 'lab_write_' || variant, 'write-' || variant || ':',
                       (batch_no - 1) * 1000 + 1, batch_no * 1000);
            started := clock_timestamp();
            EXECUTE stmt;
            GET DIAGNOSTICS inserted = ROW_COUNT;
            elapsed := extract(epoch FROM (clock_timestamp() - started)) * 1000.0;
            INSERT INTO lab_write_samples VALUES (variant, batch_no, elapsed, inserted);
        END LOOP;
    END LOOP;
END $$;

\echo '========== WRITE TIMINGS: 12 insert batches per variant =========='
SELECT variant, count(*) AS batches, sum(rows_inserted) AS inserted,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY elapsed_ms)::numeric, 3) AS median_batch_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY elapsed_ms)::numeric, 3) AS p95_batch_ms,
       round((1000.0 * count(*) / sum(elapsed_ms))::numeric, 3) AS batches_per_second
FROM lab_write_samples GROUP BY variant ORDER BY variant;
\echo '========== WRITE STORAGE FOOTPRINT =========='
SELECT relname, pg_size_pretty(pg_relation_size(oid)) AS bytes_on_disk
FROM pg_class WHERE relname IN (
  'lab_write_plain_pkey','lab_write_indexed_pkey','lab_write_keyset_idx',
  'lab_payments_keyset_idx','lab_history_merchant_payment_idx')
ORDER BY relname;

\echo '========== CHECK DATASET COUNTS =========='
SELECT 'read payments plain' AS dataset, count(*) FROM lab_payments_plain
UNION ALL SELECT 'read payments indexed', count(*) FROM lab_payments_indexed
UNION ALL SELECT 'read history plain', count(*) FROM lab_history_plain
UNION ALL SELECT 'read history indexed', count(*) FROM lab_history_indexed
UNION ALL SELECT 'write plain', count(*) FROM lab_write_plain
UNION ALL SELECT 'write indexed', count(*) FROM lab_write_indexed;

\echo 'NOTE: p95 is a sample percentile, not a production latency SLO.'
