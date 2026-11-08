-- Simulated ledger: tenant-bound append-only journals.
-- The deferrable constraint checks run at transaction commit, after all postings.
-- No settlement or real money movement is implied by these records.

CREATE UNIQUE INDEX uq_payments_merchant_id
    ON payments (merchant_id, id);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    merchant_id VARCHAR(128) NOT NULL,
    payment_id UUID NOT NULL,
    event_id UUID NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finalized BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_ledger_event_per_merchant UNIQUE (merchant_id, event_id),
    CONSTRAINT fk_ledger_payment_owner FOREIGN KEY (merchant_id, payment_id)
        REFERENCES payments (merchant_id, id)
);

CREATE INDEX idx_ledger_entries_owner_payment
    ON ledger_entries (merchant_id, payment_id, created_at, id);

CREATE TABLE ledger_postings (
    entry_id UUID NOT NULL REFERENCES ledger_entries (id),
    line_number INTEGER NOT NULL CHECK (line_number > 0),
    account_id VARCHAR(128) NOT NULL CHECK (length(trim(account_id)) > 0),
    side VARCHAR(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (entry_id, line_number)
);

CREATE OR REPLACE FUNCTION ledger_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Ledger journal records are immutable';
END;
$$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION ledger_allow_finalization_only() RETURNS trigger AS $$
BEGIN
    IF OLD.finalized = FALSE AND NEW.finalized = TRUE
        AND ROW(OLD.id, OLD.merchant_id, OLD.payment_id, OLD.event_id, OLD.currency, OLD.created_at)
            IS NOT DISTINCT FROM
            ROW(NEW.id, NEW.merchant_id, NEW.payment_id, NEW.event_id, NEW.currency, NEW.created_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Finalized ledger journal records are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_finalize
    BEFORE UPDATE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_allow_finalization_only();

CREATE TRIGGER trg_ledger_entries_delete
    BEFORE DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE OR REPLACE FUNCTION ledger_posting_insert_guard() RETURNS trigger AS $$
DECLARE
    is_finalized BOOLEAN;
BEGIN
    -- The row lock serializes posting inserts with finalization.
    SELECT finalized INTO is_finalized
    FROM ledger_entries WHERE id = NEW.entry_id FOR UPDATE;
    IF is_finalized THEN
        RAISE EXCEPTION 'Cannot append postings after ledger finalization';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_postings_insert_guard
    BEFORE INSERT ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION ledger_posting_insert_guard();

CREATE TRIGGER trg_ledger_postings_immutable
    BEFORE UPDATE OR DELETE ON ledger_postings
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE OR REPLACE FUNCTION ledger_assert_balanced(p_entry_id UUID) RETURNS void AS $$
DECLARE
    debit_total NUMERIC;
    credit_total NUMERIC;
    line_count BIGINT;
BEGIN
    IF NOT (SELECT finalized FROM ledger_entries WHERE id = p_entry_id) THEN
        RAISE EXCEPTION 'Journal must be finalized before commit'
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*),
           COALESCE(SUM(amount) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE side = 'CREDIT'), 0)
    INTO line_count, debit_total, credit_total
    FROM ledger_postings WHERE entry_id = p_entry_id;

    IF line_count < 2 OR debit_total <= 0 OR debit_total <> credit_total THEN
        RAISE EXCEPTION 'Unbalanced ledger entry %', p_entry_id
            USING ERRCODE = '23514';
    END IF;
END;
$$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION ledger_check_entry_balance() RETURNS trigger AS $$
BEGIN
    PERFORM ledger_assert_balanced(NEW.id);
    RETURN NULL;
END;
$$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION ledger_check_posting_balance() RETURNS trigger AS $$
BEGIN
    PERFORM ledger_assert_balanced(NEW.entry_id);
    RETURN NULL;
END;
$$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_entry_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_balance();

CREATE CONSTRAINT TRIGGER trg_ledger_posting_balance
    AFTER INSERT ON ledger_postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_posting_balance();
