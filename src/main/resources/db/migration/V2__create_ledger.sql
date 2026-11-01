-- Journal entries and postings are append-only. Corrections are new (reversing) entries.
CREATE TABLE journal_entries (
    id           UUID PRIMARY KEY,
    type         TEXT        NOT NULL CHECK (type IN ('TRANSFER', 'PAYMENT', 'REFUND', 'FUNDING', 'REVERSAL')),
    reference_id UUID,
    description  TEXT,
    currency     CHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX journal_entries_reference_idx ON journal_entries (reference_id) WHERE reference_id IS NOT NULL;

CREATE TABLE postings (
    id               UUID PRIMARY KEY,
    journal_entry_id UUID        NOT NULL REFERENCES journal_entries (id),
    account_id       UUID        NOT NULL REFERENCES accounts (id),
    -- Signed amount in minor units: positive = debit, negative = credit. Never zero.
    amount_minor     BIGINT      NOT NULL CHECK (amount_minor <> 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Statement pagination: WHERE account_id = ? ORDER BY created_at DESC, id DESC
CREATE INDEX postings_account_created_idx ON postings (account_id, created_at, id);
CREATE INDEX postings_journal_entry_idx ON postings (journal_entry_id);

-- Database-level safety net for the double-entry invariant. The application (LedgerService)
-- validates first; this trigger guarantees that even a buggy code path or a manual INSERT
-- cannot commit an unbalanced entry.
--
-- It is a DEFERRED constraint trigger: it runs at COMMIT, after all postings of the entry
-- have been inserted. A normal (immediate) trigger would fire after the first posting, when
-- the entry is necessarily still unbalanced.
CREATE FUNCTION ledger_check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_sum            NUMERIC;
    v_count          INTEGER;
    v_wrong_currency INTEGER;
BEGIN
    SELECT COALESCE(SUM(p.amount_minor), 0), COUNT(*)
    INTO v_sum, v_count
    FROM postings p
    WHERE p.journal_entry_id = NEW.journal_entry_id;

    IF v_count < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least 2 are required', NEW.journal_entry_id, v_count
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_sum <> 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: postings sum to %', NEW.journal_entry_id, v_sum
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT COUNT(*)
    INTO v_wrong_currency
    FROM postings p
             JOIN accounts a ON a.id = p.account_id
             JOIN journal_entries j ON j.id = p.journal_entry_id
    WHERE p.journal_entry_id = NEW.journal_entry_id
      AND a.currency <> j.currency;

    IF v_wrong_currency > 0 THEN
        RAISE EXCEPTION 'journal entry % mixes currencies', NEW.journal_entry_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER postings_must_balance
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION ledger_check_entry_balanced();

-- Immutability: reject UPDATE and DELETE on the ledger tables.
CREATE FUNCTION ledger_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW
EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW
EXECUTE FUNCTION ledger_reject_mutation();
