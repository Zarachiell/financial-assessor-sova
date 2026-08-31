ALTER TABLE cash_flow_settlements
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN note TEXT,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by_user_id UUID,
    ADD COLUMN cancellation_reason TEXT,
    ADD CONSTRAINT fk_cash_flow_settlements_cancelled_by_household
        FOREIGN KEY (cancelled_by_user_id, household_id) REFERENCES users (id, household_id),
    ADD CONSTRAINT ck_cash_flow_settlements_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    ADD CONSTRAINT ck_cash_flow_settlements_note CHECK (
        note IS NULL OR (btrim(note) <> '' AND char_length(note) <= 500)
    ),
    ADD CONSTRAINT ck_cash_flow_settlements_cancellation CHECK (
        (status = 'ACTIVE' AND cancelled_at IS NULL AND cancelled_by_user_id IS NULL AND cancellation_reason IS NULL) OR
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancelled_by_user_id IS NOT NULL AND
            cancellation_reason IS NOT NULL AND btrim(cancellation_reason) <> '' AND
            char_length(cancellation_reason) <= 500)
    );

ALTER TABLE cash_flow_settlements
    DROP CONSTRAINT uq_cash_flow_settlements_occurrence;

CREATE UNIQUE INDEX uq_cash_flow_settlements_active_occurrence
    ON cash_flow_settlements (household_id, cash_flow_entry_id, scheduled_on)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_cash_flow_settlements_household_status_scheduled
    ON cash_flow_settlements (household_id, status, scheduled_on, settled_at);

DROP INDEX uq_transactions_household_source_reference;

CREATE UNIQUE INDEX uq_transactions_household_active_source_reference
    ON transactions (household_id, source, source_reference_id)
    WHERE source_reference_id IS NOT NULL AND deleted_at IS NULL;
